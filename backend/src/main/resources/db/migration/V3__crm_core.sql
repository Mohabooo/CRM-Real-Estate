-- =====================================================================================
-- V3  Epic 2 — CRM core
--
-- Creates leads, customers and activities (doc 20, Epic 2; doc 22 section 2, CRM).
--
-- The tasks table listed alongside these in doc 22 is NOT created here: no Epic 2 story
-- needs it, doc 23's resource map has no /tasks endpoint, and "set a next action" in
-- E2-S3 is leads.next_action_at. It arrives with the epic that uses it.
--
-- Conventions follow V2: tenant_id NOT NULL everywhere, composite foreign keys carrying
-- tenant_id, row-level security ENABLED and FORCED, constraints in the same migration as
-- the table they protect.
-- =====================================================================================

-- =====================================================================================
-- leads
--
-- Doc 16 section 1: a lead is unqualified demand — a phone number with an interest,
-- possibly a duplicate, possibly never real. Keeping it separate from customers means
-- funnel noise never reaches the customer book.
--
-- stage and status are separate on purpose. Doc 18 section 1 runs
-- new → assigned → contacted → qualified → reserved → converted | disqualified, and
-- doc 22 indexes leads on `status = 'active'`. So stage carries the position in the
-- funnel and status carries whether the lead is still in it — which is what makes
-- "leaves the funnel, retained" expressible without deleting anything.
-- =====================================================================================

CREATE TABLE leads (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    branch_id           UUID,
    owner_user_id       UUID,
    name                TEXT        NOT NULL,

    -- Both spellings are kept. phone is what the person typed, which is what an agent
    -- recognises on screen; phone_normalized is the canonical form everything matches on.
    -- Doc 16 names the first and doc 22 the second; storing one would lose the other.
    phone               TEXT        NOT NULL,
    phone_normalized    TEXT        NOT NULL,

    email               TEXT,
    source              TEXT,
    stage               TEXT        NOT NULL DEFAULT 'new',
    status              TEXT        NOT NULL DEFAULT 'active',
    next_action_at      TIMESTAMPTZ,
    first_contact_at    TIMESTAMPTZ,
    disqualified_reason TEXT,
    interest            JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_user_id  UUID,
    updated_by_user_id  UUID,

    CONSTRAINT chk_leads_name_present CHECK (length(btrim(name)) > 0),
    CONSTRAINT chk_leads_phone_present CHECK (length(btrim(phone_normalized)) > 0),
    CONSTRAINT chk_leads_email_shape CHECK (
        email IS NULL OR (email = lower(btrim(email)) AND position('@' IN email) > 1)),

    CONSTRAINT chk_leads_stage CHECK (
        stage IN ('new', 'assigned', 'contacted', 'qualified', 'reserved')),

    CONSTRAINT chk_leads_status CHECK (
        status IN ('active', 'converted', 'disqualified')),

    -- Doc 18 section 1 requires a reason to disqualify. Enforced here so a lead cannot
    -- leave the funnel without one, whatever path removed it.
    CONSTRAINT chk_leads_disqualified_has_reason CHECK (
        status <> 'disqualified' OR length(btrim(coalesce(disqualified_reason, ''))) > 0),

    -- An assigned lead has an owner. Doc 18: assignment requires an owner "active and in
    -- scope", so a lead past 'new' without one would be unattributable work.
    CONSTRAINT chk_leads_assigned_has_owner CHECK (
        stage = 'new' OR owner_user_id IS NOT NULL),

    CONSTRAINT uq_leads_tenant_id UNIQUE (tenant_id, id),

    CONSTRAINT fk_leads_branch_same_tenant FOREIGN KEY (tenant_id, branch_id)
        REFERENCES branches (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_leads_owner_same_tenant FOREIGN KEY (tenant_id, owner_user_id)
        REFERENCES users (tenant_id, id) ON DELETE RESTRICT
);

COMMENT ON COLUMN leads.source IS
    'Free text: no document defines a closed set of lead sources, so none is invented here.';
COMMENT ON COLUMN leads.interest IS
    'Budget, unit type, location preferences (doc 16 section 4). Shape is not yet specified.';

-- Doc 22 section 6, verbatim: the agent queue and the stale queue.
CREATE INDEX idx_leads_owner_stage ON leads (tenant_id, owner_user_id, stage);
CREATE INDEX idx_leads_next_action ON leads (tenant_id, next_action_at)
    WHERE status = 'active';

-- Duplicate detection (E2-S1). Not unique: doc 07 A5 and doc 19 are explicit that a
-- duplicate warns rather than blocks, so two leads may legitimately share a number.
CREATE INDEX idx_leads_phone ON leads (tenant_id, phone_normalized);
CREATE INDEX idx_leads_branch_status ON leads (tenant_id, branch_id, status);

-- =====================================================================================
-- customers
--
-- A person with an actual commercial relationship. Arrives by conversion from a lead or
-- directly (doc 19). source_lead_id is the link E2-S4 requires, and it is nullable
-- because a customer created directly never had a lead.
-- =====================================================================================

CREATE TABLE customers (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID        NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    name_ar            TEXT,
    name_en            TEXT,
    phone              TEXT        NOT NULL,
    phone_normalized   TEXT        NOT NULL,
    email              TEXT,

    -- Ciphertext, never the number. Restricted beyond record access and audited on
    -- access (doc 28 section 6 and section 13).
    national_id_enc    TEXT,

    address            TEXT,
    source_lead_id     UUID,
    status             TEXT        NOT NULL DEFAULT 'active',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_user_id UUID,
    updated_by_user_id UUID,

    -- The system is bilingual; a customer needs a name in at least one language, and
    -- requiring both would block a legitimate record for the sake of tidiness.
    CONSTRAINT chk_customers_has_a_name CHECK (
        length(btrim(coalesce(name_ar, ''))) > 0 OR length(btrim(coalesce(name_en, ''))) > 0),

    CONSTRAINT chk_customers_phone_present CHECK (length(btrim(phone_normalized)) > 0),
    CONSTRAINT chk_customers_email_shape CHECK (
        email IS NULL OR (email = lower(btrim(email)) AND position('@' IN email) > 1)),
    CONSTRAINT chk_customers_status CHECK (status IN ('active', 'inactive')),

    CONSTRAINT uq_customers_tenant_id UNIQUE (tenant_id, id),

    CONSTRAINT fk_customers_source_lead_same_tenant FOREIGN KEY (tenant_id, source_lead_id)
        REFERENCES leads (tenant_id, id) ON DELETE RESTRICT
);

-- One customer per converted lead. A second conversion of the same lead would create a
-- duplicate customer book entry, which is precisely what the lead/customer split exists
-- to prevent. Partial, so directly-created customers are unaffected.
CREATE UNIQUE INDEX uniq_customer_per_source_lead
    ON customers (tenant_id, source_lead_id)
    WHERE source_lead_id IS NOT NULL;

-- Doc 22 section 6: duplicate detection.
CREATE INDEX idx_customers_phone ON customers (tenant_id, phone_normalized);
CREATE INDEX idx_customers_status ON customers (tenant_id, status);

-- =====================================================================================
-- activities
--
-- An append-only-in-practice log of contact with a lead or customer. The subject is
-- polymorphic because the same note applies to either, and later epics add deals.
-- Constrained to the types that exist now rather than left open.
-- =====================================================================================

CREATE TABLE activities (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID        NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    subject_type       TEXT        NOT NULL,
    subject_id         UUID        NOT NULL,
    type               TEXT        NOT NULL,
    body               TEXT,
    occurred_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    user_id            UUID,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_user_id UUID,
    updated_by_user_id UUID,

    -- E2-S3 names call, meeting, WhatsApp summary and note. The story says "types
    -- include", so this set is expected to grow; growing it is a migration, which is the
    -- point — an activity type nobody agreed to should not appear by accident.
    CONSTRAINT chk_activities_type CHECK (
        type IN ('call', 'meeting', 'whatsapp', 'note')),

    CONSTRAINT chk_activities_subject_type CHECK (
        subject_type IN ('Lead', 'Customer')),

    CONSTRAINT fk_activities_user_same_tenant FOREIGN KEY (tenant_id, user_id)
        REFERENCES users (tenant_id, id) ON DELETE RESTRICT
);

-- The timeline query: everything about one subject, newest first.
CREATE INDEX idx_activities_subject
    ON activities (tenant_id, subject_type, subject_id, occurred_at DESC);
CREATE INDEX idx_activities_user
    ON activities (tenant_id, user_id, occurred_at DESC);

-- =====================================================================================
-- updated_at maintenance, reusing the function V2 installed
-- =====================================================================================

CREATE TRIGGER trg_leads_updated_at BEFORE UPDATE ON leads
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_customers_updated_at BEFORE UPDATE ON customers
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_activities_updated_at BEFORE UPDATE ON activities
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- =====================================================================================
-- Row-level security — same shape as V2, for the same reasons.
-- =====================================================================================

ALTER TABLE leads      ENABLE ROW LEVEL SECURITY;
ALTER TABLE customers  ENABLE ROW LEVEL SECURITY;
ALTER TABLE activities ENABLE ROW LEVEL SECURITY;

ALTER TABLE leads      FORCE ROW LEVEL SECURITY;
ALTER TABLE customers  FORCE ROW LEVEL SECURITY;
ALTER TABLE activities FORCE ROW LEVEL SECURITY;

CREATE POLICY leads_tenant_isolation ON leads
    USING (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid);

CREATE POLICY customers_tenant_isolation ON customers
    USING (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid);

CREATE POLICY activities_tenant_isolation ON activities
    USING (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid);
