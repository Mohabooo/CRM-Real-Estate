-- =====================================================================================
-- V4  Epic 3 — Commercial models and inventory
--
-- Creates developers, projects, phases and units (doc 20, Epic 3; doc 22 section 2).
--
-- This is the migration where the commercial model stops being a lone enum in Epic 0
-- and acquires the row that carries it. Two constraints below are the whole point of
-- the epic and are stated in the schema rather than in a service, because a rule that
-- lives only in application code is eventually bypassed by the next endpoint:
--
--   C10  a developer is required exactly where the project belongs to one, and
--        forbidden where it does not
--   C9   a unit code is unique within its project
--
-- Conventions follow V2 and V3: tenant_id NOT NULL everywhere, composite foreign keys
-- carrying tenant_id, row-level security ENABLED and FORCED, constraints in the same
-- migration as the table they protect.
--
-- The reservations and deals tables are NOT created here. Doc 22's C1 and C2 — one
-- active deal and one active reservation per unit — are partial unique indexes on
-- those tables, and they arrive with them in Epics 4 and 5. What Epic 3 owns is the
-- unit's own half of the invariant: units.status, its allowed values, and the
-- conditional transition the service uses so that exactly one of several concurrent
-- claims can win (E3-S5).
-- =====================================================================================

-- =====================================================================================
-- developers
--
-- An EXTERNAL party that owns inventory the tenant sells (doc 16 section 6). Populated
-- only for brokered projects; a tenant selling its own stock never creates one, and
-- creating a Developer row to represent the tenant itself would be a self-referential
-- abstraction serving nothing (doc 25 section 3).
-- =====================================================================================

CREATE TABLE developers (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID        NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    name               TEXT        NOT NULL,
    contact            JSONB       NOT NULL DEFAULT '{}'::jsonb,
    payment_terms_note TEXT,
    is_active          BOOLEAN     NOT NULL DEFAULT true,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_user_id UUID,
    updated_by_user_id UUID,

    CONSTRAINT chk_developers_name_present CHECK (length(btrim(name)) > 0),
    CONSTRAINT uq_developers_tenant_id UNIQUE (tenant_id, id)
);

-- Doc 16 also lists default_commission_rule_id on the developer. It is not created
-- here: commission_rules is Epic 8's table, and a UUID column with no foreign key
-- behind it is a promise the schema cannot keep. E3-S2's "an inbound commission rule
-- is required before a deal on its inventory can activate" is a deal-activation
-- precondition, enforced where activation lives.
COMMENT ON COLUMN developers.contact IS
    'Contact names, phones and emails (doc 16 section 6). Shape is not yet specified.';

CREATE UNIQUE INDEX uniq_developer_name_per_tenant
    ON developers (tenant_id, lower(btrim(name)));

-- =====================================================================================
-- projects
--
-- Carries the commercial model (doc 25 section 3). Project-level rather than
-- tenant-level because a real company holds both kinds of stock at once: a brokerage
-- selling developer inventory may also own a small resale book. Tenant-level
-- configuration would force such a company into two tenants, fragmenting the very
-- customers, agents and dashboards this product exists to unify.
-- =====================================================================================

CREATE TABLE projects (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID        NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    commercial_model   TEXT        NOT NULL,
    developer_id       UUID,
    name_ar            TEXT,
    name_en            TEXT,
    location           TEXT,
    delivery_date      DATE,
    status             TEXT        NOT NULL DEFAULT 'draft',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_user_id UUID,
    updated_by_user_id UUID,

    -- The system is bilingual; a project needs a name in at least one language, and
    -- requiring both would block a legitimate record for the sake of tidiness.
    CONSTRAINT chk_projects_has_a_name CHECK (
        length(btrim(coalesce(name_ar, ''))) > 0 OR length(btrim(coalesce(name_en, ''))) > 0),

    -- The valid codes are duplicated from the CommercialModel enum on purpose. The
    -- application is not permitted to reference that enum outside its own package, so
    -- the database states the vocabulary independently rather than trusting a caller.
    CONSTRAINT chk_projects_commercial_model CHECK (
        commercial_model IN ('own_inventory', 'brokered_inventory')),

    CONSTRAINT chk_projects_status CHECK (
        status IN ('draft', 'active', 'sold_out', 'inactive')),

    -- C10, doc 22 section 7. The requested architecture change, enforced structurally:
    -- a developer exactly where the project belongs to one, and nowhere else.
    CONSTRAINT chk_projects_model_developer CHECK (
        (commercial_model = 'brokered_inventory' AND developer_id IS NOT NULL) OR
        (commercial_model = 'own_inventory'      AND developer_id IS NULL)),

    CONSTRAINT uq_projects_tenant_id UNIQUE (tenant_id, id),

    CONSTRAINT fk_projects_developer_same_tenant FOREIGN KEY (tenant_id, developer_id)
        REFERENCES developers (tenant_id, id) ON DELETE RESTRICT
);

COMMENT ON COLUMN projects.commercial_model IS
    'Immutable once the project has deals (doc 25 section 3): changing it would '
    'retroactively alter the meaning of financial records already written.';

CREATE INDEX idx_projects_status ON projects (tenant_id, status);
CREATE INDEX idx_projects_developer ON projects (tenant_id, developer_id)
    WHERE developer_id IS NOT NULL;

-- =====================================================================================
-- phases
--
-- Sub-grouping within a project: building, zone, launch batch (doc 16 section 8).
-- Deliberately thin — it is a label with a delivery date, not a second project.
-- =====================================================================================

CREATE TABLE phases (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID        NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    project_id         UUID        NOT NULL,
    name               TEXT        NOT NULL,
    delivery_date      DATE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_user_id UUID,
    updated_by_user_id UUID,

    CONSTRAINT chk_phases_name_present CHECK (length(btrim(name)) > 0),
    CONSTRAINT uq_phases_tenant_id UNIQUE (tenant_id, id),

    CONSTRAINT fk_phases_project_same_tenant FOREIGN KEY (tenant_id, project_id)
        REFERENCES projects (tenant_id, id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uniq_phase_name_per_project
    ON phases (tenant_id, project_id, lower(btrim(name)));

-- =====================================================================================
-- units
--
-- The atomic sellable item (doc 16 section 9).
--
-- status is never written by a client. Doc 23 is explicit that it is a consequence of
-- reservation and deal actions, and doc 18 section 2 gives the machine:
--   available -> reserved -> sold, blocked off to the side, returning to available on
--   release or cancellation.
-- =====================================================================================

CREATE TABLE units (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    project_id         UUID         NOT NULL,
    phase_id           UUID,
    code               TEXT         NOT NULL,
    type               TEXT,
    area_sqm           NUMERIC(10, 2),
    floor              TEXT,
    view               TEXT,

    -- money_amount is NUMERIC(18,2) from V1. Never a float: binary floating point
    -- cannot represent decimal money exactly, and this column feeds deal values.
    list_price         money_amount NOT NULL,

    status             TEXT         NOT NULL DEFAULT 'available',
    blocked_reason     TEXT,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by_user_id UUID,
    updated_by_user_id UUID,

    CONSTRAINT chk_units_code_present CHECK (length(btrim(code)) > 0),
    CONSTRAINT chk_units_list_price_positive CHECK (list_price > 0),
    CONSTRAINT chk_units_area_positive CHECK (area_sqm IS NULL OR area_sqm > 0),

    CONSTRAINT chk_units_status CHECK (
        status IN ('available', 'reserved', 'sold', 'blocked')),

    -- Doc 18 section 2: a block requires a reason. Without it "blocked" is a state
    -- nobody can explain three weeks later, which is how units quietly leave inventory.
    CONSTRAINT chk_units_blocked_has_reason CHECK (
        status <> 'blocked' OR length(btrim(coalesce(blocked_reason, ''))) > 0),

    CONSTRAINT uq_units_tenant_id UNIQUE (tenant_id, id),

    CONSTRAINT fk_units_project_same_tenant FOREIGN KEY (tenant_id, project_id)
        REFERENCES projects (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_units_phase_same_tenant FOREIGN KEY (tenant_id, phase_id)
        REFERENCES phases (tenant_id, id) ON DELETE RESTRICT
);

-- C9, doc 22 section 7. Also what makes a CSV re-import idempotent enough to be
-- useful: the second attempt at a row reports a duplicate instead of creating one.
CREATE UNIQUE INDEX uniq_unit_code_per_project
    ON units (tenant_id, project_id, lower(btrim(code)));

-- Doc 22 section 6, verbatim: inventory browse and status counts.
CREATE INDEX idx_units_project_status ON units (tenant_id, project_id, status);

-- E3-S4 browses across projects and both commercial models in one list, and the
-- default view excludes sold and blocked. Partial, because that default view is the
-- query that runs constantly and the excluded rows only grow.
CREATE INDEX idx_units_sellable ON units (tenant_id, type, list_price)
    WHERE status IN ('available', 'reserved');

-- =====================================================================================
-- A phase must belong to the same project as the unit that references it
--
-- The composite foreign keys above keep a unit's project and its phase inside one
-- tenant, but nothing yet stops a unit in project A pointing at a phase of project B.
-- A trigger rather than a check constraint, because the rule spans two rows.
-- =====================================================================================

CREATE OR REPLACE FUNCTION assert_unit_phase_belongs_to_project() RETURNS TRIGGER AS $$
DECLARE
    phase_project UUID;
BEGIN
    IF NEW.phase_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT project_id INTO phase_project
    FROM phases
    WHERE id = NEW.phase_id AND tenant_id = NEW.tenant_id;

    IF phase_project IS NULL OR phase_project <> NEW.project_id THEN
        RAISE EXCEPTION 'Phase % does not belong to project %', NEW.phase_id, NEW.project_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_units_phase_matches_project
    BEFORE INSERT OR UPDATE OF phase_id, project_id ON units
    FOR EACH ROW EXECUTE FUNCTION assert_unit_phase_belongs_to_project();

-- =====================================================================================
-- updated_at maintenance, reusing the function V2 installed
-- =====================================================================================

CREATE TRIGGER trg_developers_updated_at BEFORE UPDATE ON developers
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_projects_updated_at BEFORE UPDATE ON projects
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_phases_updated_at BEFORE UPDATE ON phases
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_units_updated_at BEFORE UPDATE ON units
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- =====================================================================================
-- Row-level security — same shape as V2 and V3, for the same reasons.
-- =====================================================================================

ALTER TABLE developers ENABLE ROW LEVEL SECURITY;
ALTER TABLE projects   ENABLE ROW LEVEL SECURITY;
ALTER TABLE phases     ENABLE ROW LEVEL SECURITY;
ALTER TABLE units      ENABLE ROW LEVEL SECURITY;

ALTER TABLE developers FORCE ROW LEVEL SECURITY;
ALTER TABLE projects   FORCE ROW LEVEL SECURITY;
ALTER TABLE phases     FORCE ROW LEVEL SECURITY;
ALTER TABLE units      FORCE ROW LEVEL SECURITY;

CREATE POLICY developers_tenant_isolation ON developers
    USING (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid);

CREATE POLICY projects_tenant_isolation ON projects
    USING (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid);

CREATE POLICY phases_tenant_isolation ON phases
    USING (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid);

CREATE POLICY units_tenant_isolation ON units
    USING (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid);
