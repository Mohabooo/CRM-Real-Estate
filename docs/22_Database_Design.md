# 22 — Database Design

**Revision 2 — September 2026.** Revised for the multi-commercial-model direction: `projects` carries a commercial model with a conditional developer, `deals` denormalises it and gains the down-payment confirmation milestone, and the two commission tables are replaced by a single `commissions` table with a direction.

**Subject:** Physical database design for PostgreSQL, derived from `16_Canonical_Domain_Model.md`. Emphasis on financial integrity — the constraints in §7 are the load-bearing part of this document.

---

## 1. Conventions

- **Primary keys:** UUID (v7 preferred for index locality) named `id`.
- **Tenant column:** every business table carries `tenant_id UUID NOT NULL` referencing `tenants(id)`.
- **Timestamps:** `created_at`, `updated_at` as `TIMESTAMPTZ NOT NULL DEFAULT now()`; `created_by_user_id`, `updated_by_user_id` on all mutable business tables.
- **Soft delete:** not used in the financial path. Business records use status columns; financial records use reversal rows.
- **Naming:** snake_case, plural table names, `<entity>_id` foreign keys.
- **Enums:** implemented as Postgres enum types or check-constrained text; either way the valid set matches `18` exactly.

---

## 2. Table inventory (MVP)

### Tenancy and identity
| Table | Notable columns |
|---|---|
| `tenants` | `name`, `status`, `settings JSONB` (aging buckets, grace days, currency, approval thresholds) |
| `branches` | `tenant_id`, `name`, `is_active` |
| `users` | `tenant_id`, `email`, `password_hash`, `name`, `phone`, `role`, `branch_id`, `is_active` |
| `invitations` | `tenant_id`, `email`, `role`, `branch_id`, `token_hash`, `expires_at`, `accepted_at` |

### CRM
| Table | Notable columns |
|---|---|
| `leads` | `tenant_id`, `branch_id`, `owner_user_id`, `name`, `phone_normalized`, `email`, `source`, `stage`, `status`, `next_action_at`, `interest JSONB` |
| `customers` | `tenant_id`, `name_ar`, `name_en`, `phone_normalized`, `email`, `national_id_enc`, `address`, `source_lead_id`, `status` |
| `activities` | `tenant_id`, `subject_type`, `subject_id`, `type`, `body`, `occurred_at`, `user_id` |
| `tasks` | `tenant_id`, `assignee_user_id`, `subject_type`, `subject_id`, `due_at`, `status` |

### Inventory
| Table | Notable columns |
|---|---|
| `developers` | `tenant_id`, `name`, `contact JSONB`, `payment_terms_note TEXT`, `is_active` — **only ever populated for brokered projects** |
| `projects` | `tenant_id`, **`commercial_model`** (`own_inventory`\|`brokered_inventory`), `developer_id` **(nullable — required iff brokered)**, `name_ar`, `name_en`, `location`, `delivery_date DATE`, `status` |
| `phases` | `tenant_id`, `project_id`, `name`, `delivery_date DATE` |
| `units` | `tenant_id`, `project_id`, `phase_id`, `code`, `type`, `area_sqm NUMERIC(10,2)`, `floor`, `view`, `list_price NUMERIC(18,2)`, `status`, `blocked_reason` |
| `unit_status_history` | `tenant_id`, `unit_id`, `from_status`, `to_status`, `reason`, `changed_by_user_id`, `changed_at` |

### Reservations and deals
| Table | Notable columns |
|---|---|
| `reservations` | `tenant_id`, `unit_id`, `lead_id`, `customer_id`, `agent_user_id`, `reserved_at`, `expires_at`, `deposit_amount NUMERIC(18,2)`, `deposit_received BOOLEAN`, `status` |
| `deals` | `tenant_id`, `branch_id`, `unit_id`, `primary_customer_id`, `agent_user_id`, `source_reservation_id`, `deal_date DATE`, `gross_value NUMERIC(18,2)`, `total_discount NUMERIC(18,2)`, `net_value NUMERIC(18,2)`, **`commercial_model`** (denormalised from project at creation), **`down_payment_confirmed_at DATE`**, **`down_payment_confirmed_by_user_id`**, `currency CHAR(3) DEFAULT 'EGP'`, `status`, `completed_at`, `cancelled_at` |
| `deal_customers` | `tenant_id`, `deal_id`, `customer_id`, `is_primary BOOLEAN`, `role` — join table present from day one (A4 in `15`) |
| `discounts` | `tenant_id`, `deal_id`, `type`, `value NUMERIC(18,4)`, `computed_amount NUMERIC(18,2)`, `reason`, `approved_by_user_id`, `applied_at`, `reverses_discount_id` |

### Payment plans and installments
| Table | Notable columns |
|---|---|
| `payment_plan_templates` | `tenant_id`, `project_id`, `unit_id`, `name`, `shorthand_label`, `plan_type`, `down_payment_type`, `down_payment_value NUMERIC(18,4)`, `installment_count INT`, `frequency`, `duration_months INT`, `delivery_payment_percent NUMERIC(7,4)`, `first_installment_offset_days INT`, `is_active` |
| `customer_payment_plans` | `tenant_id`, `deal_id`, `source_template_id`, `version INT`, `net_value`, `down_payment_amount`, `delivery_payment_amount`, `financed_amount`, `installment_count`, `frequency`, `first_due_date DATE`, `rounding_policy`, `status`, `superseded_by_id` — all money columns `NUMERIC(18,2)` |
| `installments` | `tenant_id`, `deal_id`, `plan_id`, `sequence_no INT`, `kind`, `due_date DATE`, `expected_amount NUMERIC(18,2)`, `allocated_amount NUMERIC(18,2) DEFAULT 0`, `status`, `void_reason` |

### Collections
| Table | Notable columns |
|---|---|
| `payments` | `tenant_id`, `deal_id`, `amount NUMERIC(18,2)`, `payment_date DATE`, `method`, `reference_no`, `proof_document_id`, `recorded_by_user_id`, `status`, `idempotency_key`, `void_of_payment_id`, `notes` — rows exist **only** for own-inventory deals (C11) |
| `payment_allocations` | `tenant_id`, `payment_id`, `installment_id`, `allocated_amount NUMERIC(18,2)`, `is_auto BOOLEAN`, `allocated_by_user_id`, `reverses_allocation_id` |
| `post_dated_cheques` *(P2)* | `tenant_id`, `payment_id`, `cheque_number`, `bank`, `maturity_date DATE`, `deposit_date DATE`, `status` |

### Commission
| Table | Notable columns |
|---|---|
| `commission_rules` | `tenant_id`, `direction` (`inbound`\|`outbound`), `scope` (`tenant`\|`developer`\|`project`\|`deal`), `scope_id`, `participant_role` (outbound only), `basis`, `calc_type` (`percent`\|`fixed`\|`tiered`), `value NUMERIC(18,4)`, `tiers JSONB`, `trigger`, `effective_from DATE`, `effective_to DATE`, `is_active` |
| `commissions` | `tenant_id`, `deal_id`, `direction`, `counterparty_type` (`developer`\|`partner`\|`user`), `counterparty_id`, `participant_role`, `rule_id`, `rule_snapshot JSONB`, `basis_amount NUMERIC(18,2)`, `rate_applied NUMERIC(18,4)`, `expected_amount NUMERIC(18,2)`, `settled_amount NUMERIC(18,2) DEFAULT 0`, `state`, `trigger_met_at`, `claimed_at`, `due_date DATE` — **one table replacing the former developer/agent pair** (A11) |
| `commission_adjustments` | `tenant_id`, `commission_id`, `amount NUMERIC(18,2)`, `reason`, `created_by_user_id` — clawbacks and corrections as offsetting rows (R-COMM-9) |

### Supporting
| Table | Notable columns |
|---|---|
| `documents` | `tenant_id`, `subject_type`, `subject_id`, `storage_key`, `filename`, `content_type`, `size_bytes`, `sensitivity`, `uploaded_by_user_id` |
| `notifications` | `tenant_id`, `user_id`, `type`, `payload JSONB`, `read_at`, `sent_at`, `dedupe_key` |
| `audit_events` | `tenant_id`, `actor_user_id`, `entity_type`, `entity_id`, `action`, `before JSONB`, `after JSONB`, `reason`, `correlation_id`, `created_at` |
| `import_jobs` | `tenant_id`, `type`, `filename`, `row_count`, `success_count`, `error_count`, `errors JSONB`, `status`, `created_by_user_id` |

*(Cancellations table arrives with P2 — `cancellations`: `deal_id`, `requested_at`, `reason`, `grace_period_end`, `total_paid_at_cancellation`, `refund_policy_ref`, `computed_refund_amount`, `approved_by_user_id`, `status`.)*

---

## 3. Key foreign keys

All FKs are `NOT NULL` unless the domain model says the relationship is optional, and all carry `ON DELETE RESTRICT` in the financial path — nothing that money references may be deleted.

```
branches.tenant_id           → tenants.id
users.branch_id              → branches.id            (nullable: owner may be unscoped)
leads.owner_user_id          → users.id
customers.source_lead_id     → leads.id               (nullable)
projects.developer_id        → developers.id
units.project_id             → projects.id
reservations.unit_id         → units.id
deals.unit_id                → units.id               RESTRICT
deals.primary_customer_id    → customers.id           RESTRICT
deals.source_reservation_id  → reservations.id        (nullable)
customer_payment_plans.deal_id → deals.id             RESTRICT
installments.plan_id         → customer_payment_plans.id  RESTRICT
installments.deal_id         → deals.id               RESTRICT  (denormalized for query performance)
payments.deal_id             → deals.id               RESTRICT
payment_allocations.payment_id     → payments.id      RESTRICT
payment_allocations.installment_id → installments.id  RESTRICT
commissions.deal_id                → deals.id         RESTRICT
commissions.rule_id                → commission_rules.id  RESTRICT
commission_adjustments.commission_id → commissions.id RESTRICT
```
`commissions.counterparty_id` is a polymorphic reference resolved by `counterparty_type` (`developers.id`, a partner record, or `users.id`). It is validated in the application and by a check constraint on the type/id pairing rather than by a single FK, since the referent varies.

---

## 4. Tenant isolation

1. **Column:** `tenant_id` on every business table, `NOT NULL`.
2. **Row-level security:** RLS enabled on every business table with a policy matching `tenant_id` against a session setting (`app.current_tenant_id`) established at connection/transaction start.
3. **Composite FKs where it matters:** foreign keys in the financial path include `tenant_id` in composite form (e.g. `payment_allocations(tenant_id, installment_id) → installments(tenant_id, id)`), making a cross-tenant reference structurally impossible rather than merely improbable.
4. **Application scoping:** repositories always filter by tenant as well. Two independent layers by design (`21`§5).
5. **Test requirement:** an automated cross-tenant leakage test runs in CI against every endpoint.

---

## 5. History and audit strategy

- **`audit_events`** is the general append-only trail (`21`§9). No `UPDATE`/`DELETE` grants on it for the application role.
- **`unit_status_history`** is a dedicated append-only log for inventory transitions (already in BRD Appendix A).
- **Financial history is intrinsic, not separate:** payments, allocations, discounts and commission adjustments are never mutated. Corrections create reversal rows (`reverses_allocation_id`, `void_of_payment_id`, `reverses_discount_id`, `commission_adjustments`). The current state is therefore always the sum of an event history, which is what makes a dispute reconstructable.
- **Plan versioning (P2):** superseded plans are retained with `superseded_by_id`; their installments move to `void` but are never deleted.

---

## 6. Indexes

**Tenant-leading composite indexes** throughout, since every query is tenant-scoped.

| Index | Purpose |
|---|---|
| `installments (tenant_id, due_date) WHERE status <> 'void'` | The workhorse for expected/overdue/forecast queries (R-EXP-1, R-OVD-2, R-FCT-1) |
| `installments (tenant_id, deal_id, sequence_no)` | Deal schedule display; allocation ordering |
| `installments (tenant_id, plan_id)` | Plan operations |
| `payments (tenant_id, payment_date) WHERE status = 'cleared'` | Actual collections by period (R-ACT-1) |
| `payments (tenant_id, deal_id)` | Deal payment history |
| `payment_allocations (tenant_id, installment_id)` | Allocation rollups |
| `payment_allocations (tenant_id, payment_id)` | Payment detail and reversal |
| `deals (tenant_id, status, deal_date)` | Pipeline and sales reporting |
| `deals (tenant_id, branch_id, status)` | Branch-scoped reporting (C4) |
| `units (tenant_id, project_id, status)` | Inventory browse and status counts |
| `leads (tenant_id, owner_user_id, stage)` | Agent queues |
| `leads (tenant_id, next_action_at) WHERE status = 'active'` | Stale queue |
| `commissions (tenant_id, direction, state)` | Commission dashboards by direction |
| `commissions (tenant_id, counterparty_type, counterparty_id, state)` | Per-agent and per-developer commission views |
| `commissions (tenant_id, deal_id)` | Full commission position on a deal in one query |
| `deals (tenant_id, commercial_model, status)` | Model-scoped financial aggregates (R-SCOPE-2) |
| `audit_events (tenant_id, entity_type, entity_id, created_at)` | Record-level audit lookups |
| `customers (tenant_id, phone_normalized)` | Duplicate detection |

---

## 7. Constraints — the financial integrity core

These are the rules that must hold regardless of application bugs.

**C1 — One active deal per unit**
```sql
CREATE UNIQUE INDEX uniq_active_deal_per_unit
  ON deals (tenant_id, unit_id)
  WHERE status IN ('draft_reserved','active');
```
*(The exact status set is finalized in implementation; the requirement is that no two simultaneously-live deals can reference one unit.)*

**C2 — One active reservation per unit**
```sql
CREATE UNIQUE INDEX uniq_active_reservation_per_unit
  ON reservations (tenant_id, unit_id)
  WHERE status IN ('pending','confirmed');
```

**C3 — One active payment plan per deal**
```sql
CREATE UNIQUE INDEX uniq_active_plan_per_deal
  ON customer_payment_plans (tenant_id, deal_id)
  WHERE status = 'active';
```

**C4 — Non-negative and bounded money**
```sql
CHECK (expected_amount >= 0)
CHECK (allocated_amount >= 0 AND allocated_amount <= expected_amount)  -- installments
CHECK (amount > 0)                                                      -- payments
CHECK (allocated_amount > 0)                                            -- payment_allocations
CHECK (net_value > 0 AND total_discount >= 0 AND total_discount < gross_value)  -- deals
```

**C5 — Allocations cannot exceed their payment**
Enforced in the allocation service inside the transaction, with a deferred constraint trigger asserting
`Σ payment_allocations.allocated_amount ≤ payments.amount` per payment. A violation aborts the transaction.

**C6 — Schedule sums to net value (R-PLAN-4)**
Asserted at plan activation by a trigger (or an activation-time service check with an equality assertion):
`down_payment + delivery_payment + Σ installments.expected_amount = plan.net_value`, exact equality, no tolerance.

**C7 — Immutability of settled financial rows**
Triggers reject `UPDATE`/`DELETE` on `payments` (except the status transitions in `18`§7), on `payment_allocations` (reversal only), and on `audit_events` (never).

**C8 — Currency**
`CHECK (currency = 'EGP')` in MVP; the column exists so the constraint relaxes to a supported-set check when multi-currency arrives (A6 in `15`).

**C9 — Unique business keys**
`UNIQUE (tenant_id, project_id, code)` on units; `UNIQUE (tenant_id, email)` on users; `UNIQUE (tenant_id, idempotency_key)` on payments.

**C10 — Commercial model and developer consistency** *(new in rev 2)*
```sql
ALTER TABLE projects ADD CONSTRAINT chk_model_developer CHECK (
  (commercial_model = 'brokered_inventory' AND developer_id IS NOT NULL) OR
  (commercial_model = 'own_inventory'      AND developer_id IS NULL)
);
```
A developer is required exactly where the project belongs to one, and forbidden where it does not — the requested architecture change, enforced structurally rather than by convention.

**C11 — Payments only on own-inventory deals** *(new in rev 2)*
Enforced by a trigger asserting that the parent deal's `commercial_model = 'own_inventory'` before any insert into `payments` (R-PAY-0). Rejecting rather than flagging is deliberate: a partially-populated ledger produces totals that look authoritative and are not (A13).

**C12 — Inbound commissions only on brokered deals** *(new in rev 2)*
Trigger asserting that a `commissions` row with `direction = 'inbound'` has a parent deal whose `commercial_model = 'brokered_inventory'` (R-COMM-5).

**C13 — Commission rule snapshot required** *(new in rev 2)*
`CHECK (rule_snapshot IS NOT NULL)` on `commissions`. An entitlement without a snapshot cannot be defended in a dispute, so it must not exist (R-COMM-4).

**C14 — Non-overlapping commission rules**
Exclusion constraint preventing two active rules with the same `(tenant_id, direction, scope, scope_id, participant_role)` and overlapping effective date ranges.

---

## 8. Money and decimal handling

- **Type:** `NUMERIC(18,2)` for all amounts. Rates and percentages use `NUMERIC(18,4)` to preserve precision before rounding.
- **No floating point anywhere in the financial path** — not in the database, not in the application, not in API serialization (amounts serialize as strings or integer minor units, never as JSON floats).
- **Rounding:** half-up to 2 decimal places at the points specified in `17` (discount computation, down payment, delivery payment) and nowhere else. **Exception:** the base installment (R-INST-1) uses *floor*, not half-up, so that the remainder is always non-negative and is added to the final installment (R-INST-2). Half-up there could produce a negative remainder requiring the last installment to be reduced — arithmetically fine, but it breaks the simpler invariant the generator asserts. Intermediate values are not pre-rounded.
- **Remainder placement:** the last installment absorbs the rounding remainder (R-INST-2), which is why C6 can demand exact equality rather than a tolerance.
- **Aggregation:** sums are computed in the database using `NUMERIC` arithmetic, never client-side over paginated results.

---

## 9. Dates and time zones

- **`due_date`, `payment_date`, `deal_date`, `delivery_date`: `DATE`** — no time component. A payment made at 23:00 Cairo on the 31st belongs to that month regardless of server time zone, and an installment due "on the 15th" has no hour.
- **Event timestamps (`created_at`, state changes, audit): `TIMESTAMPTZ`** stored in UTC.
- **Period boundaries** for reporting are computed in the tenant's time zone (Africa/Cairo), so "this month" means the brokerage's month.
- **Month arithmetic** for schedule generation follows R-INST-6 (clamp to month end, retain original day-of-month for subsequent steps) and is implemented in one shared utility with its own test suite — not re-implemented per call site.

---

## 10. Migrations and data integrity operations

- Forward-only, versioned migrations; every migration reviewed for lock behaviour on tables that will hold production money data.
- **Backfills touching financial tables require:** a dry-run report, a reconciliation query proving totals before and after, and an audit entry recording the operation.
- **Reconciliation job (recommended, runs nightly):** verifies for every active plan that C6 still holds, that every installment's `allocated_amount` equals the sum of its non-reversed allocations, and that no payment is over-allocated. Any discrepancy raises an alert (`21`§14) rather than auto-correcting — silent auto-correction of financial data is how you lose the ability to explain what happened.
