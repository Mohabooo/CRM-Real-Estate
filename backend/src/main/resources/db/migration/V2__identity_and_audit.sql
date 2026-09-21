-- =====================================================================================
-- V2  Epic 1 — Tenancy and identity
--
-- Creates the five tables Epic 1 owns: tenants, branches, users, invitations and
-- audit_events. Nothing from a later epic appears here (doc 20, Epic 1).
--
-- Constraints ship in the same migration as the table they protect (doc 28 section 5),
-- so every table below arrives complete: foreign keys, checks, indexes and its
-- row-level security policy.
--
-- Two independent isolation layers (doc 22 section 4, doc 28 section 4):
--   (a) row-level security keyed on app.current_tenant_id, enabled AND forced below;
--   (b) repository-level tenant predicates in application code.
-- Neither is trusted alone. FORCE matters: without it the table owner bypasses its own
-- policies, so the application's own role would read across tenants and an RLS test
-- would pass while proving nothing.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- Shared conventions
--
-- Primary keys default to gen_random_uuid() (v4). Doc 22 section 1 prefers v7 for index
-- locality; neither PostgreSQL 16 nor Java 21 generates v7 without a dependency, so v4
-- is used and the preference is recorded as unmet rather than silently dropped.
-- -------------------------------------------------------------------------------------

-- =====================================================================================
-- tenants
--
-- The tenant is the isolation root, so it carries no tenant_id of its own: its identity
-- IS the tenant. Its RLS policy therefore matches on id rather than tenant_id.
-- =====================================================================================

CREATE TABLE tenants (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                     TEXT        NOT NULL,
    status                   TEXT        NOT NULL,
    default_commercial_model TEXT        NOT NULL,
    settings                 JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_user_id       UUID,
    updated_by_user_id       UUID,

    CONSTRAINT chk_tenants_name_present CHECK (length(btrim(name)) > 0),

    -- Lifecycle from doc 16 section 1. Held as check-constrained text rather than a
    -- PostgreSQL enum so that adding a state is an ordinary migration.
    CONSTRAINT chk_tenants_status CHECK (
        status IN ('provisioning', 'active', 'suspended', 'closed')),

    -- Doc 16 section 1 gives the tenant a default commercial model used to pre-fill new
    -- projects. It is stored as an opaque code and is never branched on outside the
    -- commercialmodel package (doc 28 section 2, rule 5).
    CONSTRAINT chk_tenants_default_commercial_model CHECK (
        default_commercial_model IN ('own_inventory', 'brokered_inventory'))
);

COMMENT ON TABLE tenants IS
    'The company using the system. Isolation root: every other business row references it.';
COMMENT ON COLUMN tenants.settings IS
    'Aging buckets, grace days, currency, approval thresholds, commission defaults (doc 16 section 1).';

-- =====================================================================================
-- branches
-- =====================================================================================

CREATE TABLE branches (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID        NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    name               TEXT        NOT NULL,
    is_active          BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_user_id UUID,
    updated_by_user_id UUID,

    CONSTRAINT chk_branches_name_present CHECK (length(btrim(name)) > 0),

    -- Target for the composite foreign keys below. A child row referencing
    -- (tenant_id, branch_id) cannot then point at a branch in another tenant: the
    -- reference is structurally impossible rather than merely unlikely
    -- (doc 22 section 4.3).
    CONSTRAINT uq_branches_tenant_id UNIQUE (tenant_id, id)
);

CREATE INDEX idx_branches_tenant_active ON branches (tenant_id, is_active);

-- =====================================================================================
-- users
--
-- A user row exists from the moment the person can authenticate. The 'invited' state in
-- the doc 16 section 3 lifecycle lives in the invitations table, not here, which is why
-- password_hash can be NOT NULL and is_active carries the remaining two states.
-- =====================================================================================

CREATE TABLE users (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID        NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    email              TEXT        NOT NULL,
    password_hash      TEXT        NOT NULL,
    name               TEXT        NOT NULL,
    phone              TEXT,
    role               TEXT        NOT NULL,
    branch_id          UUID,
    is_active          BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_user_id UUID,
    updated_by_user_id UUID,

    CONSTRAINT chk_users_name_present CHECK (length(btrim(name)) > 0),
    CONSTRAINT chk_users_email_shape CHECK (email = lower(btrim(email)) AND position('@' IN email) > 1),

    -- The seven MVP roles from doc 16 section 3. Not extensible by configuration:
    -- custom RBAC is P2 (doc 19, Permissions).
    CONSTRAINT chk_users_role CHECK (role IN (
        'OWNER', 'BRANCH_MANAGER', 'TEAM_LEADER', 'SALES_AGENT',
        'OPERATIONS', 'FINANCE', 'PLATFORM_ADMIN')),

    -- C9 (doc 22 section 7): one account per email per tenant. The same person may hold
    -- accounts in two tenants, which is why this is scoped rather than global.
    CONSTRAINT uq_users_tenant_email UNIQUE (tenant_id, email),

    CONSTRAINT uq_users_tenant_id UNIQUE (tenant_id, id),

    -- Composite: the branch must belong to the same tenant as the user.
    CONSTRAINT fk_users_branch_same_tenant FOREIGN KEY (tenant_id, branch_id)
        REFERENCES branches (tenant_id, id) ON DELETE RESTRICT
);

CREATE INDEX idx_users_tenant_active ON users (tenant_id, is_active);
CREATE INDEX idx_users_tenant_branch ON users (tenant_id, branch_id);

COMMENT ON COLUMN users.branch_id IS
    'Nullable: an owner or platform admin is not scoped to one branch (doc 22 section 3).';

-- =====================================================================================
-- invitations
--
-- Carries the pre-acceptance half of the user lifecycle. The token is stored only as a
-- hash: a leaked database backup must not yield usable invitation links, exactly as a
-- password table must not yield passwords.
-- =====================================================================================

CREATE TABLE invitations (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID        NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    email              TEXT        NOT NULL,
    role               TEXT        NOT NULL,
    branch_id          UUID,
    token_hash         TEXT        NOT NULL,
    expires_at         TIMESTAMPTZ NOT NULL,
    accepted_at        TIMESTAMPTZ,
    accepted_user_id   UUID,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_user_id UUID,
    updated_by_user_id UUID,

    CONSTRAINT chk_invitations_email_shape CHECK (
        email = lower(btrim(email)) AND position('@' IN email) > 1),

    CONSTRAINT chk_invitations_role CHECK (role IN (
        'OWNER', 'BRANCH_MANAGER', 'TEAM_LEADER', 'SALES_AGENT',
        'OPERATIONS', 'FINANCE', 'PLATFORM_ADMIN')),

    CONSTRAINT chk_invitations_expiry_after_creation CHECK (expires_at > created_at),

    -- Acceptance is atomic: either both facts are recorded or neither is. A row with an
    -- accepted_at and no user would be an invitation nobody can trace to an account.
    CONSTRAINT chk_invitations_acceptance_complete CHECK (
        (accepted_at IS NULL AND accepted_user_id IS NULL)
        OR (accepted_at IS NOT NULL AND accepted_user_id IS NOT NULL)),

    -- Global rather than tenant-scoped: a token is presented before any tenant is known,
    -- so it must identify exactly one invitation across the whole installation.
    CONSTRAINT uq_invitations_token_hash UNIQUE (token_hash),

    CONSTRAINT fk_invitations_branch_same_tenant FOREIGN KEY (tenant_id, branch_id)
        REFERENCES branches (tenant_id, id) ON DELETE RESTRICT,

    CONSTRAINT fk_invitations_accepted_user_same_tenant FOREIGN KEY (tenant_id, accepted_user_id)
        REFERENCES users (tenant_id, id) ON DELETE RESTRICT
);

-- At most one outstanding invitation per address per tenant. Partial, so a new
-- invitation may be issued once an earlier one has been accepted.
CREATE UNIQUE INDEX uniq_open_invitation_per_email
    ON invitations (tenant_id, email)
    WHERE accepted_at IS NULL;

CREATE INDEX idx_invitations_tenant_expires ON invitations (tenant_id, expires_at)
    WHERE accepted_at IS NULL;

COMMENT ON COLUMN invitations.token_hash IS
    'SHA-256 of the single-use token. The token itself is shown once, at issue, and never stored.';

-- =====================================================================================
-- audit_events
--
-- Append-only (doc 22 section 5, doc 28 section 13). The trigger below is the guarantee:
-- application code cannot update or delete an audit row even by accident, and a
-- production deployment additionally withholds those grants from the application role.
-- =====================================================================================

CREATE TABLE audit_events (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID        NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    actor_user_id  UUID,
    entity_type    TEXT        NOT NULL,
    entity_id      UUID        NOT NULL,
    action         TEXT        NOT NULL,
    before         JSONB,
    after          JSONB,
    reason         TEXT,
    correlation_id TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_audit_entity_type_present CHECK (length(btrim(entity_type)) > 0),
    CONSTRAINT chk_audit_action_present CHECK (length(btrim(action)) > 0)
);

COMMENT ON COLUMN audit_events.actor_user_id IS
    'Null only for SYS-actor transitions (doc 18). Never null for a user-initiated action.';
COMMENT ON TABLE audit_events IS
    'Append-only trail. No updated_at column: a row that can be amended is not an audit record.';

CREATE INDEX idx_audit_events_entity
    ON audit_events (tenant_id, entity_type, entity_id, created_at DESC);
CREATE INDEX idx_audit_events_tenant_created
    ON audit_events (tenant_id, created_at DESC);
CREATE INDEX idx_audit_events_actor
    ON audit_events (tenant_id, actor_user_id, created_at DESC);

CREATE OR REPLACE FUNCTION audit_events_are_append_only() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'audit_events is append-only; % is not permitted (doc 28 section 13)', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_events_no_update
    BEFORE UPDATE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_are_append_only();

CREATE TRIGGER trg_audit_events_no_delete
    BEFORE DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_are_append_only();

-- =====================================================================================
-- updated_at maintenance
--
-- Set in the database rather than trusted from the application, so a row cannot be
-- modified without its timestamp moving. audit_events is excluded: it has no updated_at
-- and no update path.
-- =====================================================================================

CREATE OR REPLACE FUNCTION set_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_tenants_updated_at BEFORE UPDATE ON tenants
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_branches_updated_at BEFORE UPDATE ON branches
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_invitations_updated_at BEFORE UPDATE ON invitations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- =====================================================================================
-- Row-level security
--
-- current_setting(..., true) returns NULL instead of raising when the setting is absent.
-- Combined with the NULL-safe comparison below, an unset tenant matches no rows at all,
-- which is the safe failure: a query with no tenant established returns nothing rather
-- than everything.
--
-- FORCE applies the policy to the table owner too. Without it the role that created the
-- tables — which in development and test is the role the application connects as — would
-- bypass every policy here.
--
-- A superuser still bypasses RLS by design in PostgreSQL. RlsIsolationIT therefore runs
-- its assertions as a dedicated non-superuser role; asserting RLS as a superuser would
-- prove nothing.
-- =====================================================================================

ALTER TABLE tenants      ENABLE ROW LEVEL SECURITY;
ALTER TABLE branches     ENABLE ROW LEVEL SECURITY;
ALTER TABLE users        ENABLE ROW LEVEL SECURITY;
ALTER TABLE invitations  ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_events ENABLE ROW LEVEL SECURITY;

ALTER TABLE tenants      FORCE ROW LEVEL SECURITY;
ALTER TABLE branches     FORCE ROW LEVEL SECURITY;
ALTER TABLE users        FORCE ROW LEVEL SECURITY;
ALTER TABLE invitations  FORCE ROW LEVEL SECURITY;
ALTER TABLE audit_events FORCE ROW LEVEL SECURITY;

CREATE POLICY tenants_tenant_isolation ON tenants
    USING (id = nullif(current_setting('app.current_tenant_id', true), '')::uuid)
    WITH CHECK (id = nullif(current_setting('app.current_tenant_id', true), '')::uuid);

CREATE POLICY branches_tenant_isolation ON branches
    USING (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid);

CREATE POLICY users_tenant_isolation ON users
    USING (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid);

CREATE POLICY invitations_tenant_isolation ON invitations
    USING (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid);

-- Audit rows are readable and insertable within the tenant; the append-only triggers
-- above already refuse update and delete regardless of policy.
CREATE POLICY audit_events_tenant_isolation ON audit_events
    USING (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid);
