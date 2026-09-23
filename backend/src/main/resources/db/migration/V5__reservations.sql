-- =====================================================================================
-- V5  Epic 4 — Reservations
--
-- A time-bound hold on a unit while a customer decides or documents are collected
-- (doc 16 section 10; doc 18 section 3).
--
-- Doc 16's framing is worth restating because it drives every decision below: a
-- reservation is informal and frequently abandoned, and it is supposed to expire
-- harmlessly. A Deal is the contractual commitment that must never disappear. They are
-- separate tables precisely so that cancellation and refund logic never lands on an
-- object designed for holds people walk away from.
--
-- What this migration does NOT create: deals. Doc 18 allows confirmed -> converted, and
-- the status is declared below, but nothing can reach it until Epic 5 adds the deal and
-- the foreign key that makes "converted" mean something. A converted reservation with no
-- deal behind it would be a lie the schema tells.
-- =====================================================================================

CREATE TABLE reservations (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    unit_id            UUID         NOT NULL,

    -- A hold is placed for somebody who may still be a lead: doc 23 spells the payload
    -- "lead_id|customer_id", and the check below enforces the "or".
    lead_id            UUID,
    customer_id        UUID,

    agent_user_id      UUID         NOT NULL,
    branch_id          UUID,

    reserved_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at         TIMESTAMPTZ  NOT NULL,

    -- Kept alongside expires_at so an extension limit is measurable. Without it, "extend
    -- within a limit" (E4-S3) would have no fixed point to measure from and a manager
    -- could walk a reservation forward a day at a time forever.
    original_expires_at TIMESTAMPTZ NOT NULL,
    extension_count    INTEGER      NOT NULL DEFAULT 0,

    -- Nullable: a hold may be placed before any money changes hands. Under
    -- brokered_inventory a recorded deposit is a confirmation that the DEVELOPER
    -- received it, not tenant cash (doc 25 section 4) — the amount is the same column,
    -- the meaning is the commercial model's, and CollectionPolicy is what reads it.
    deposit_amount     money_amount,
    deposit_received   BOOLEAN      NOT NULL DEFAULT false,

    status             TEXT         NOT NULL DEFAULT 'pending',
    closed_reason      TEXT,
    closed_at          TIMESTAMPTZ,

    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by_user_id UUID,
    updated_by_user_id UUID,

    CONSTRAINT chk_reservations_status CHECK (
        status IN ('pending', 'confirmed', 'converted', 'released', 'expired', 'cancelled')),

    -- Exactly one party. Both would leave every downstream join ambiguous about who the
    -- hold is actually for; neither would make the reservation unattributable.
    CONSTRAINT chk_reservations_one_party CHECK (
        num_nonnulls(lead_id, customer_id) = 1),

    CONSTRAINT chk_reservations_expiry_after_start CHECK (expires_at > reserved_at),
    CONSTRAINT chk_reservations_extension_not_negative CHECK (extension_count >= 0),

    -- An extension moves expires_at forward, never back before where it started.
    CONSTRAINT chk_reservations_expiry_not_rolled_back CHECK (
        expires_at >= original_expires_at),

    CONSTRAINT chk_reservations_deposit_positive CHECK (
        deposit_amount IS NULL OR deposit_amount > 0),

    -- Doc 18 section 3: releasing requires a reason. Stated here so a unit cannot come
    -- back into inventory without anybody able to say why three weeks later.
    CONSTRAINT chk_reservations_released_has_reason CHECK (
        status <> 'released' OR length(btrim(coalesce(closed_reason, ''))) > 0),

    CONSTRAINT uq_reservations_tenant_id UNIQUE (tenant_id, id),

    CONSTRAINT fk_reservations_unit_same_tenant FOREIGN KEY (tenant_id, unit_id)
        REFERENCES units (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_reservations_lead_same_tenant FOREIGN KEY (tenant_id, lead_id)
        REFERENCES leads (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_reservations_customer_same_tenant FOREIGN KEY (tenant_id, customer_id)
        REFERENCES customers (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_reservations_agent_same_tenant FOREIGN KEY (tenant_id, agent_user_id)
        REFERENCES users (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_reservations_branch_same_tenant FOREIGN KEY (tenant_id, branch_id)
        REFERENCES branches (tenant_id, id) ON DELETE RESTRICT
);

-- =====================================================================================
-- C2 — one active reservation per unit (doc 22, section 7)
--
-- The other half of the double-sell guard. Epic 3 could only enforce the unit's side of
-- it, with a conditional UPDATE on units.status, because this table did not exist. Now
-- both layers are present and neither depends on the other being correct: the index
-- makes a second live hold impossible even if the application forgets to check, and the
-- conditional update makes a second claim on the unit impossible even if the index is
-- ever dropped.
--
-- Partial on purpose. An expired or released reservation must not block the next one;
-- the whole point of a hold is that it stops mattering when it ends.
-- =====================================================================================

CREATE UNIQUE INDEX uniq_active_reservation_per_unit
    ON reservations (tenant_id, unit_id)
    WHERE status IN ('pending', 'confirmed');

-- The agent's own queue, and the expiry sweep's working set.
CREATE INDEX idx_reservations_agent ON reservations (tenant_id, agent_user_id, status);
CREATE INDEX idx_reservations_expiring ON reservations (tenant_id, expires_at)
    WHERE status IN ('pending', 'confirmed');
CREATE INDEX idx_reservations_unit ON reservations (tenant_id, unit_id, status);

CREATE TRIGGER trg_reservations_updated_at BEFORE UPDATE ON reservations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- =====================================================================================
-- Row-level security — same shape as V2 to V4, for the same reasons.
-- =====================================================================================

ALTER TABLE reservations ENABLE ROW LEVEL SECURITY;
ALTER TABLE reservations FORCE ROW LEVEL SECURITY;

CREATE POLICY reservations_tenant_isolation ON reservations
    USING (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid);

-- =====================================================================================
-- Letting the expiry sweep find out which tenants it has work for
--
-- E4-S2 needs work to happen with nobody asking for it, and that collides with the
-- isolation design in a way worth stating plainly. Every connection is stamped with a
-- tenant, and the policies treat an unset tenant as matching nothing — correctly, because
-- the safe failure for "no tenant" is to see nothing. But a sweep has no tenant until it
-- knows which tenants exist, so a cross-tenant query would return zero rows in production
-- while appearing to work in tests, where the connection is a superuser and bypasses RLS
-- entirely. That is the worst kind of bug: silent, and invisible exactly where it is
-- tested.
--
-- So the sweep gets one narrow, deliberate exemption: with app.platform_task set, it may
-- read the tenant REGISTRY — ids and nothing that matters — and only for SELECT. Every
-- other table stays exactly as isolated as before, and the sweep does its actual work one
-- tenant at a time under the ordinary policy.
--
-- Two things keep this from becoming a hole. TenantAwareDataSource clears
-- app.platform_task on every connection it hands out, so no request can inherit it from a
-- pooled connection. And it is additive to a SELECT policy only: it grants no INSERT,
-- UPDATE or DELETE anywhere, on any table, including this one.
-- =====================================================================================

CREATE POLICY tenants_platform_registry_read ON tenants
    FOR SELECT
    USING (current_setting('app.platform_task', true) = 'reservation-expiry');

COMMENT ON POLICY tenants_platform_registry_read ON tenants IS
    'Read-only, tenant registry only. Lets the reservation expiry sweep enumerate tenants; '
    'grants nothing on any other table. See V5 and TenantAwareDataSource.';
