-- =====================================================================================
-- V7 — Deals and payment plans (Epic 5)
--
-- The heart of the system: a deal freezes a price, a plan turns it into a schedule of
-- what the customer owes, and activation takes the unit off the market.
--
-- One boundary is drawn deliberately and runs through the whole migration. These tables
-- hold EXPECTED OBLIGATIONS and nothing else — what is contractually due, on what date,
-- for how much. What was actually paid, how it was allocated, what was reversed and what
-- is outstanding or overdue belong to payments and collections, and arrive with Epic 6
-- in their own migration. Doc 22 lists installments.allocated_amount; it is not created
-- here, because a column that is zero for a whole epic invites somebody to read it as an
-- answer, and because the two halves have genuinely different lifecycles.
-- =====================================================================================


-- =====================================================================================
-- deals
--
-- commercial_model is denormalised from the project at creation and never changes after
-- (doc 22, rev 2). A deal signed under one arrangement is not retrospectively governed by
-- another because somebody edited the project, and every downstream financial rule reads
-- the deal's copy rather than chasing the project's current value.
--
-- The model affects what SURROUNDS the schedule — who collects, which commissions exist,
-- whether payments may be recorded at all — and never the schedule arithmetic itself
-- (doc 27 section 2).
-- =====================================================================================

CREATE TABLE deals (
    id                             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                      UUID        NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    branch_id                      UUID,
    unit_id                        UUID        NOT NULL,
    primary_customer_id            UUID        NOT NULL,
    agent_user_id                  UUID        NOT NULL,

    -- Nullable: doc 20 asks for a deal "from a unit and customer", and a walk-in buyer who
    -- never had a hold is an ordinary case, not a missing reservation.
    source_reservation_id          UUID,

    deal_date                      DATE        NOT NULL,
    gross_value                    money_amount NOT NULL,
    total_discount                 money_amount NOT NULL DEFAULT 0,
    net_value                      money_amount NOT NULL,

    commercial_model               TEXT        NOT NULL,
    currency                       TEXT        NOT NULL DEFAULT 'EGP',
    status                         TEXT        NOT NULL DEFAULT 'draft',

    -- R-DISC-5: recorded when a discount above the tenant's threshold has been approved.
    discount_approved_at           TIMESTAMPTZ,
    discount_approved_by_user_id   UUID,

    -- R-DP-1 (BRK): a milestone the tenant learns, not a ledger entry.
    down_payment_confirmed_at      DATE,
    down_payment_confirmed_by_user_id UUID,

    activated_at                   TIMESTAMPTZ,
    completed_at                   TIMESTAMPTZ,
    cancelled_at                   TIMESTAMPTZ,
    cancelled_reason               TEXT,

    created_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_user_id             UUID,
    updated_by_user_id             UUID,

    -- Doc 18 section 4: draft -> active -> completed, or cancelled from either.
    CONSTRAINT chk_deals_status CHECK (
        status IN ('draft', 'active', 'completed', 'cancelled')),

    CONSTRAINT chk_deals_commercial_model CHECK (
        commercial_model IN ('own_inventory', 'brokered_inventory')),

    -- TEXT rather than CHAR(3), like every other code column in this schema. The check
    -- below already pins the value exactly, so a fixed width buys nothing — and bpchar
    -- carries PostgreSQL's blank-padding comparison semantics, which is a subtlety nobody
    -- wants on a currency code (doc 17 N9: EGP only, until a second currency is a decision
    -- somebody makes deliberately).
    CONSTRAINT chk_deals_currency CHECK (currency = 'EGP'),

    -- C4. net_value > 0 is R-DISC-4; a discount equal to the gross would make a deal worth
    -- nothing, which is a mistake rather than a price.
    CONSTRAINT chk_deals_money CHECK (
        gross_value > 0
        AND total_discount >= 0
        AND total_discount < gross_value
        AND net_value > 0),

    -- R-DISC-3, enforced here rather than trusted to the service. The two columns are read
    -- independently by reporting, and a deal whose net does not follow from its own gross
    -- and discount is unreconcilable.
    CONSTRAINT chk_deals_net_follows_from_gross CHECK (
        net_value = gross_value - total_discount),

    CONSTRAINT chk_deals_approval_complete CHECK (
        (discount_approved_at IS NULL AND discount_approved_by_user_id IS NULL)
        OR (discount_approved_at IS NOT NULL AND discount_approved_by_user_id IS NOT NULL)),

    CONSTRAINT chk_deals_confirmation_complete CHECK (
        (down_payment_confirmed_at IS NULL AND down_payment_confirmed_by_user_id IS NULL)
        OR (down_payment_confirmed_at IS NOT NULL
            AND down_payment_confirmed_by_user_id IS NOT NULL)),

    CONSTRAINT chk_deals_cancellation_has_reason CHECK (
        cancelled_at IS NULL OR length(btrim(cancelled_reason)) > 0),

    CONSTRAINT uq_deals_tenant_id UNIQUE (tenant_id, id),

    CONSTRAINT fk_deals_unit_same_tenant FOREIGN KEY (tenant_id, unit_id)
        REFERENCES units (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_deals_customer_same_tenant FOREIGN KEY (tenant_id, primary_customer_id)
        REFERENCES customers (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_deals_agent_same_tenant FOREIGN KEY (tenant_id, agent_user_id)
        REFERENCES users (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_deals_branch_same_tenant FOREIGN KEY (tenant_id, branch_id)
        REFERENCES branches (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_deals_reservation_same_tenant FOREIGN KEY (tenant_id, source_reservation_id)
        REFERENCES reservations (tenant_id, id) ON DELETE RESTRICT
);

-- C1 — one live deal per unit. The other half of the double-sell guard, and the half
-- Epic 3 could not express because this table did not exist. A draft counts: two agents
-- drafting against the same unit is the situation the constraint is for.
CREATE UNIQUE INDEX uniq_active_deal_per_unit
    ON deals (tenant_id, unit_id)
    WHERE status IN ('draft', 'active');

CREATE INDEX idx_deals_status_date ON deals (tenant_id, status, deal_date);
CREATE INDEX idx_deals_branch_status ON deals (tenant_id, branch_id, status);
CREATE INDEX idx_deals_model_status ON deals (tenant_id, commercial_model, status);
CREATE INDEX idx_deals_customer ON deals (tenant_id, primary_customer_id);

COMMENT ON COLUMN deals.commercial_model IS
    'Copied from the project at creation and never changed. Affects collection ownership '
    'and commissions, never the schedule arithmetic (doc 27 section 2).';
COMMENT ON COLUMN deals.gross_value IS
    'The unit list price frozen at creation (R-VAL-1). Later price changes do not reach it.';


-- =====================================================================================
-- deal_discounts
--
-- R-DISC-2 says multiple discounts sum, and each percentage is computed against gross
-- rather than compounded. That needs the individual discounts, not just their total: a
-- deal showing 150,000 off tells nobody whether it was one 5% concession or three
-- separate ones, and the second is a conversation somebody had to approve.
--
-- deals.total_discount stays as the denormalised sum, because every financial aggregate
-- reads it and none of them want a join.
-- =====================================================================================

CREATE TABLE deal_discounts (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID        NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    deal_id            UUID        NOT NULL,

    kind               TEXT        NOT NULL,
    -- For a percentage this is the rate; for a fixed discount it is the amount. The
    -- resolved money is in amount, so nothing downstream recomputes it.
    value              NUMERIC(18,4) NOT NULL,
    amount             money_amount NOT NULL,
    reason             TEXT,

    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_user_id UUID,
    updated_by_user_id UUID,

    CONSTRAINT chk_deal_discounts_kind CHECK (kind IN ('percent', 'fixed')),
    CONSTRAINT chk_deal_discounts_value CHECK (value > 0),
    CONSTRAINT chk_deal_discounts_amount CHECK (amount > 0),
    CONSTRAINT chk_deal_discounts_percent_bound CHECK (kind <> 'percent' OR value <= 100),

    CONSTRAINT uq_deal_discounts_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_deal_discounts_deal_same_tenant FOREIGN KEY (tenant_id, deal_id)
        REFERENCES deals (tenant_id, id) ON DELETE RESTRICT
);

CREATE INDEX idx_deal_discounts_deal ON deal_discounts (tenant_id, deal_id);


-- =====================================================================================
-- payment_plan_templates
--
-- A template is a shape, not a schedule. Doc 16 Part A's separation is the rule TPL-001
-- protects: editing a template must never reach a customer's existing plan. That holds
-- structurally here because customer_payment_plans copies the terms rather than pointing
-- at them, and source_template_id is a provenance record, not a live reference.
-- =====================================================================================

CREATE TABLE payment_plan_templates (
    id                            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                     UUID        NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,

    -- Both optional: a template may be tenant-wide, scoped to a project, or to one unit.
    project_id                    UUID,

    name                          TEXT        NOT NULL,
    -- "10% down, 8 years quarterly, 5% on delivery" — how the market states a plan, kept
    -- because that is what an agent recognises on a price list (E5-S3).
    shorthand_label               TEXT,

    down_payment_type             TEXT        NOT NULL,
    down_payment_value            NUMERIC(18,4) NOT NULL,
    delivery_payment_percent      rate_percentage NOT NULL DEFAULT 0,
    installment_count             INT         NOT NULL,
    frequency                     TEXT        NOT NULL,
    first_installment_offset_days INT,

    is_active                     BOOLEAN     NOT NULL DEFAULT TRUE,

    created_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_user_id            UUID,
    updated_by_user_id            UUID,

    CONSTRAINT chk_plan_templates_name CHECK (length(btrim(name)) > 0),
    CONSTRAINT chk_plan_templates_down_type CHECK (down_payment_type IN ('percent', 'fixed')),
    CONSTRAINT chk_plan_templates_down_value CHECK (down_payment_value >= 0),
    CONSTRAINT chk_plan_templates_down_percent_bound CHECK (
        down_payment_type <> 'percent' OR down_payment_value <= 100),
    CONSTRAINT chk_plan_templates_delivery_bound CHECK (
        delivery_payment_percent >= 0 AND delivery_payment_percent <= 100),
    CONSTRAINT chk_plan_templates_count CHECK (installment_count >= 1),
    CONSTRAINT chk_plan_templates_frequency CHECK (
        frequency IN ('monthly', 'quarterly', 'semi_annual', 'annual')),
    CONSTRAINT chk_plan_templates_offset CHECK (
        first_installment_offset_days IS NULL OR first_installment_offset_days >= 0),

    CONSTRAINT uq_plan_templates_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_plan_templates_name_per_tenant UNIQUE (tenant_id, name),
    CONSTRAINT fk_plan_templates_project_same_tenant FOREIGN KEY (tenant_id, project_id)
        REFERENCES projects (tenant_id, id) ON DELETE RESTRICT
);

CREATE INDEX idx_plan_templates_active ON payment_plan_templates (tenant_id, is_active);

COMMENT ON COLUMN payment_plan_templates.first_installment_offset_days IS
    'Null means one frequency interval, which doc 17 section 12 gives as the default.';


-- =====================================================================================
-- customer_payment_plans
--
-- One customer's instance, with its terms copied at instantiation. The copy is what makes
-- TPL-001 structural: a template edit cannot reach these columns, because nothing here
-- reads the template again.
-- =====================================================================================

CREATE TABLE customer_payment_plans (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                 UUID        NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    deal_id                   UUID        NOT NULL,

    -- Provenance only. RESTRICT rather than CASCADE so a template with live instances
    -- cannot be removed (TPL-003).
    source_template_id        UUID,

    version                   INT         NOT NULL DEFAULT 1,
    net_value                 money_amount NOT NULL,
    down_payment_amount       money_amount NOT NULL,
    delivery_payment_amount   money_amount NOT NULL,
    financed_amount           money_amount NOT NULL,
    installment_count         INT         NOT NULL,
    frequency                 TEXT        NOT NULL,
    first_due_date            DATE        NOT NULL,
    status                    TEXT        NOT NULL DEFAULT 'draft',
    superseded_by_id          UUID,

    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_user_id        UUID,
    updated_by_user_id        UUID,

    -- Doc 18 section 5.
    CONSTRAINT chk_plans_status CHECK (
        status IN ('draft', 'active', 'superseded', 'closed', 'cancelled')),
    CONSTRAINT chk_plans_frequency CHECK (
        frequency IN ('monthly', 'quarterly', 'semi_annual', 'annual')),
    CONSTRAINT chk_plans_count CHECK (installment_count >= 1),

    CONSTRAINT chk_plans_money CHECK (
        net_value > 0
        AND down_payment_amount >= 0
        AND delivery_payment_amount >= 0
        AND financed_amount >= 0),

    -- R-PLAN-3, at the database. The application asserts R-PLAN-4 over the generated rows;
    -- this asserts the decomposition the rows were generated from, so a plan whose parts
    -- do not add up cannot be stored even if some future caller skips the generator.
    CONSTRAINT chk_plans_decomposition CHECK (
        financed_amount = net_value - down_payment_amount - delivery_payment_amount),

    CONSTRAINT uq_plans_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_plans_deal_same_tenant FOREIGN KEY (tenant_id, deal_id)
        REFERENCES deals (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_plans_template_same_tenant FOREIGN KEY (tenant_id, source_template_id)
        REFERENCES payment_plan_templates (tenant_id, id) ON DELETE RESTRICT
);

-- C3 — one active plan per deal.
CREATE UNIQUE INDEX uniq_active_plan_per_deal
    ON customer_payment_plans (tenant_id, deal_id)
    WHERE status = 'active';

-- And at most one draft, so regenerating replaces rather than accumulates (TPL-006).
CREATE UNIQUE INDEX uniq_draft_plan_per_deal
    ON customer_payment_plans (tenant_id, deal_id)
    WHERE status = 'draft';

CREATE INDEX idx_plans_deal ON customer_payment_plans (tenant_id, deal_id);


-- =====================================================================================
-- installments
--
-- What the customer is contractually expected to pay, and when. Nothing about what they
-- actually paid: no allocated amount, no paid status, no outstanding figure. Those are
-- payments and collections, and they arrive with Epic 6.
--
-- The down payment and the delivery tranche are rows here like any other, which is what
-- makes R-PLAN-4 expressible as one sum over one table. Holding the down payment
-- somewhere else would mean every caller that wanted a total had to remember to add it
-- back, and one of them would not.
-- =====================================================================================

CREATE TABLE installments (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID        NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    deal_id            UUID        NOT NULL,
    plan_id            UUID        NOT NULL,

    sequence_no        INT         NOT NULL,
    kind               TEXT        NOT NULL,
    due_date           DATE        NOT NULL,
    expected_amount    money_amount NOT NULL,
    status             TEXT        NOT NULL DEFAULT 'pending',
    void_reason        TEXT,

    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_user_id UUID,
    updated_by_user_id UUID,

    CONSTRAINT chk_installments_kind CHECK (
        kind IN ('down_payment', 'installment', 'delivery')),

    -- Only the two states an expected obligation can be in before money exists. Epic 6
    -- adds the paid states with the allocation machinery that gives them meaning; a
    -- 'paid' an installment could never reach would be a lie in a check constraint.
    CONSTRAINT chk_installments_status CHECK (status IN ('pending', 'void')),

    CONSTRAINT chk_installments_amount CHECK (expected_amount >= 0),
    CONSTRAINT chk_installments_sequence CHECK (sequence_no >= 1),
    CONSTRAINT chk_installments_void_has_reason CHECK (
        status <> 'void' OR length(btrim(void_reason)) > 0),

    CONSTRAINT uq_installments_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_installments_sequence_per_plan UNIQUE (tenant_id, plan_id, sequence_no),

    CONSTRAINT fk_installments_deal_same_tenant FOREIGN KEY (tenant_id, deal_id)
        REFERENCES deals (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_installments_plan_same_tenant FOREIGN KEY (tenant_id, plan_id)
        REFERENCES customer_payment_plans (tenant_id, id) ON DELETE RESTRICT
);

-- Doc 22 section 6's workhorse index for expected, overdue and forecast queries.
CREATE INDEX idx_installments_due ON installments (tenant_id, due_date)
    WHERE status <> 'void';
CREATE INDEX idx_installments_deal_sequence
    ON installments (tenant_id, deal_id, sequence_no);
CREATE INDEX idx_installments_plan ON installments (tenant_id, plan_id);

COMMENT ON TABLE installments IS
    'Expected customer obligations only. Payments, allocations and outstanding figures '
    'belong to Epic 6 and are not represented here.';


-- =====================================================================================
-- Row-level security — enabled and forced, like every business table before them
-- =====================================================================================

ALTER TABLE deals                  ENABLE ROW LEVEL SECURITY;
ALTER TABLE deal_discounts         ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment_plan_templates ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer_payment_plans ENABLE ROW LEVEL SECURITY;
ALTER TABLE installments           ENABLE ROW LEVEL SECURITY;

ALTER TABLE deals                  FORCE ROW LEVEL SECURITY;
ALTER TABLE deal_discounts         FORCE ROW LEVEL SECURITY;
ALTER TABLE payment_plan_templates FORCE ROW LEVEL SECURITY;
ALTER TABLE customer_payment_plans FORCE ROW LEVEL SECURITY;
ALTER TABLE installments           FORCE ROW LEVEL SECURITY;

CREATE POLICY deals_tenant_isolation ON deals
    USING (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid);

CREATE POLICY deal_discounts_tenant_isolation ON deal_discounts
    USING (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid);

CREATE POLICY payment_plan_templates_tenant_isolation ON payment_plan_templates
    USING (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid);

CREATE POLICY customer_payment_plans_tenant_isolation ON customer_payment_plans
    USING (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid);

CREATE POLICY installments_tenant_isolation ON installments
    USING (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid);
