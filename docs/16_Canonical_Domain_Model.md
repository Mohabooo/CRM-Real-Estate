# 16 — Canonical Domain Model

**Revision 2 — September 2026.** Revised for the multi-commercial-model product direction. Changes from revision 1: `Developer` is now optional; `Project` carries a commercial model; the two-leg commission model (`DeveloperCommission` + `AgentCommissionPayout`) is replaced by a **single unified `Commission` entity** with a direction. Where `08_Data_Model.md` differs, **this document wins**.

**Read alongside:** `25_Commercial_Models.md` (which models exist and what they switch) and `26_Commission_Rules_and_Splits.md` (the commission engine in detail).

---

## Part A — The nine distinctions that matter most

**Lead vs. Customer.** A *Lead* is unqualified demand — a phone number with an interest, possibly a duplicate, possibly never real. A *Customer* is a person with an actual commercial relationship. One becomes the other at or before deal creation. Keeping them separate means funnel noise never pollutes the customer book, and disqualifying a lead never touches a financial record.

**Reservation vs. Deal.** A *Reservation* is a time-bound hold on a Unit — informal, frequently abandoned, expires on its own. A *Deal* is the confirmed transaction with contractual consequences. Different objects because they have incompatible lifecycles: reservations are supposed to expire harmlessly; deals are supposed to never disappear.

**PaymentPlanTemplate vs. CustomerPaymentPlan.** The *Template* is the reusable offer ("10% down, 8 years, quarterly"). The *CustomerPaymentPlan* is the frozen instance applied to one Deal. Editing a template must never alter a live customer's schedule — the instance exists to make that impossible. **This separation holds in both commercial models.**

**Installment vs. Payment.** An *Installment* is **expected** money. A *Payment* is **actual** money. Never the same row, linked only through `PaymentAllocation`. This is the three-layer principle and it is the most important structural rule in the system.

**Payment vs. PaymentAllocation.** A *Payment* is one real-world money event; an *Allocation* is the decision about which installment it satisfies. Separating them lets one payment cover three installments, or three cover one, without ambiguity.

**Outstanding vs. Overdue.** Neither is an entity; both are computed (`17`). Outstanding is owed but possibly not yet late; Overdue is the late subset. Storing either is a defect.

**Inbound vs. outbound Commission.** *Inbound* is revenue the tenant earns from an external party (only in `brokered_inventory`). *Outbound* is what the tenant owes its own people or partners (both models). One entity, one direction field — because their lifecycles, rule resolution, snapshotting and clawback behaviour are the same shape, and keeping them together makes "the commission position on this deal" a single query.

**Commercial model vs. entity type.** The model lives on the *Project*, not on the tenant and not in a class hierarchy. A tenant may hold both kinds of project. This is the architecture change requested in the revision: **a developer is required only where the project actually belongs to one.**

**Deal value vs. tenant revenue.** In `own_inventory` the tenant's revenue is the deal value. In `brokered_inventory` it is the commission only. The dashboard must never blend them (`25`§7).

---

## Part B — Entity definitions

### 1. Tenant — MVP
**Purpose:** The company using the system — a brokerage, a developer, or a company doing both.
**Key fields:** `id`, `name`, `default_commercial_model`, `settings JSONB` (aging buckets, grace days, currency, approval thresholds, commission defaults), `status`.
**Lifecycle:** `provisioning → active → suspended → closed`.
**Changed in rev 2:** added `default_commercial_model`, used to pre-fill new projects.

### 2. Branch — MVP
**Purpose:** Organisational office; drives visibility scoping and reporting.
**Key fields:** `id`, `tenant_id`, `name`, `is_active`.

### 3. User — MVP
**Purpose:** A person who logs in; carries role and branch scope; may be a commission participant.
**Key fields:** `id`, `tenant_id`, `name`, `email`, `phone`, `role`, `branch_id`, `is_active`.
**Relationships:** owns Leads; is agent-of-record on Deals; is a payee on outbound Commissions.
**Lifecycle:** `invited → active → deactivated`. Deactivation never orphans financial records.
**Roles (MVP):** Owner/CEO, Branch Manager, Team Leader, Sales Agent, Operations, Finance, Platform Admin.

### 4. Lead — MVP
**Purpose:** Unqualified or in-progress demand.
**Key fields:** `id`, `tenant_id`, `branch_id`, `name`, `phone`, `email`, `source`, `stage`, `owner_user_id`, `interest`, `next_action_at`, `status`.
**Lifecycle:** `18`§1 — `new → assigned → contacted → qualified → (reserved) → converted | disqualified`.
**Model-agnostic.**

### 5. Customer — MVP
**Purpose:** The counterparty on Deals and subject of payment history.
**Key fields:** `id`, `tenant_id`, names (AR/EN), phones, `email`, `national_id_enc`, `address`, `source_lead_id`, `status`.
**Relationships:** many Deals; linked to Deals via `deal_customers` (join table from day one; single primary in MVP UI).
**Model-agnostic.**

### 6. Developer — MVP, **OPTIONAL**
**Purpose:** An **external** party that owns inventory the tenant sells. Counterparty for inbound commission.
**Key fields:** `id`, `tenant_id`, `name`, `contact JSONB`, `default_commission_rule_id`, `payment_terms_note`, `is_active`.
**Relationships:** has many Projects (brokered only); referenced by inbound Commissions.
**Changed in rev 2 — the key architecture change:** no longer mandatory. Required **only** when a project's commercial model is `brokered_inventory`. For `own_inventory` projects, `developer_id` is null and no Developer record exists. A tenant selling only its own stock never creates a Developer.

### 7. Project — MVP
**Purpose:** A development containing sellable Units. **Carries the commercial model.**
**Key fields:** `id`, `tenant_id`, `commercial_model` (`own_inventory` | `brokered_inventory`), `developer_id` (nullable, required iff brokered), `name_ar`, `name_en`, `location`, `delivery_date`, `status`.
**Constraint:** `(commercial_model = 'brokered_inventory') = (developer_id IS NOT NULL)` — enforced in the database.
**Lifecycle:** `draft → active → sold_out | inactive`.
**Changed in rev 2:** added `commercial_model`; `developer_id` became conditional.
**Rule:** `commercial_model` is immutable after the project has deals (`25`§3).

### 8. Phase — MVP (thin)
**Purpose:** Sub-grouping within a Project (building, zone, launch batch).
**Key fields:** `id`, `project_id`, `name`, `delivery_date`.

### 9. Unit — MVP
**Purpose:** The atomic sellable item.
**Key fields:** `id`, `tenant_id`, `project_id`, `phase_id`, `code`, `type`, `area_sqm`, `floor`, `view`, `list_price`, `status`, `blocked_reason`.
**Lifecycle:** `18`§2 — `available → reserved → sold → (cancelled → available)`, plus `blocked`.
**Hard invariant:** at most one active Reservation and one active Deal per Unit, enforced in the database. Applies in **both** models — a brokered unit can be double-sold just as damagingly as an owned one.

### 10. Reservation — MVP
**Purpose:** A time-bound hold while a customer decides or documents are collected.
**Key fields:** `id`, `tenant_id`, `unit_id`, `lead_id`/`customer_id`, `agent_user_id`, `reserved_at`, `expires_at`, `deposit_amount`, `deposit_received`, `status`.
**Lifecycle:** `18`§3.
**Model-agnostic**, except that in `brokered_inventory` a recorded deposit is a *confirmation that the developer received it*, not tenant cash.

### 11. Deal — MVP
**Purpose:** The confirmed sale; the spine of the money model.
**Key fields:** `id`, `tenant_id`, `branch_id`, `unit_id`, `primary_customer_id`, `agent_user_id`, `source_reservation_id`, `deal_date`, `gross_value`, `total_discount`, `net_value`, `commercial_model` (**denormalised from Project at creation**), `down_payment_confirmed_at`, `down_payment_confirmed_by_user_id`, `status`, `completed_at`, `cancelled_at`.
**Relationships:** one Unit, one primary Customer (many via `deal_customers`), one active CustomerPaymentPlan, many Payments (own-inventory), many Commissions, optional Cancellation.
**Lifecycle:** `18`§4 — `draft → active → completed | cancelled`.
**Changed in rev 2:** `collection_owner` replaced by the denormalised `commercial_model` (the model determines the collector, so a separate field was redundant); added the down-payment confirmation milestone used by the inbound commission trigger (`25`§4).
**Why denormalise the model onto the Deal:** the deal's financial rules must not change if the project is ever edited, and every financial query needs the model without joining to Project.

### 12. PaymentPlanTemplate — MVP
**Purpose:** A reusable, sellable payment scheme.
**Key fields:** `id`, `tenant_id`, `project_id` (nullable), `unit_id` (nullable), `name`, `shorthand_label` ("60/40", "10% + 8 yrs"), `plan_type` (`cash` | `down_plus_installments` | `hybrid`), `down_payment_type`, `down_payment_value`, `installment_count`, `frequency`, `duration_months`, `delivery_payment_percent`, `first_installment_offset_days`, `is_active`.
**Lifecycle:** `draft → active → archived`. **Archiving never affects existing instances.**
**Model-agnostic — identical in both models.**

### 13. CustomerPaymentPlan — MVP
**Purpose:** The frozen, deal-specific instance that installments are generated from.
**Key fields:** `id`, `tenant_id`, `deal_id`, `source_template_id`, `version`, `net_value`, `down_payment_amount`, `delivery_payment_amount`, `financed_amount`, `installment_count`, `frequency`, `first_due_date`, `rounding_policy`, `status`, `superseded_by_id`.
**Lifecycle:** `18`§5 — `draft → active → superseded | closed | cancelled`.
**Invariant:** exactly one `active` plan per Deal.
**Model-agnostic.** In `brokered_inventory` the generated schedule is reference information (`25`§4) but is generated by identical logic.

### 14. Installment — MVP
**Purpose:** One expected obligation — the "expected layer."
**Key fields:** `id`, `tenant_id`, `deal_id`, `plan_id`, `sequence_no`, `kind` (`down_payment`|`installment`|`delivery`|`fee`), `due_date`, `expected_amount`, `allocated_amount`, `status`, `void_reason`.
**Lifecycle:** `18`§6 — `scheduled → partially_paid → paid`, or `void`. `overdue` is **derived, never stored**.
**Model note:** in `brokered_inventory`, `allocated_amount` stays zero because payments are not tracked; status therefore stays `scheduled`, and overdue is **not computed** for those deals (`17`§7).

### 15. Payment — MVP (`own_inventory`)
**Purpose:** One actual money event — the "actual layer."
**Key fields:** `id`, `tenant_id`, `deal_id`, `amount`, `payment_date`, `method`, `reference_no`, `proof_document_id`, `recorded_by_user_id`, `status` (`pending`|`cleared`|`bounced`|`void`), `idempotency_key`, `notes`.
**Lifecycle:** `18`§7.
**Changed in rev 2:** the `collected_by` field is removed — in `own_inventory` the tenant always collects, and in `brokered_inventory` payments are not recorded at all in MVP. Recording a payment against a brokered deal is **rejected** rather than stored with a flag, because a half-populated ledger is worse than an empty one: it invites people to read totals that mean nothing.

### 16. PaymentAllocation — MVP (`own_inventory`)
**Purpose:** Records which installment a slice of a payment satisfies.
**Key fields:** `id`, `tenant_id`, `payment_id`, `installment_id`, `allocated_amount`, `allocated_by_user_id`, `is_auto`, `reverses_allocation_id`.
**Rule:** never edited in place; corrections reverse and re-create.

### 17. Discount — MVP
**Purpose:** A negotiated reduction recorded as an auditable event, not a silent price edit.
**Key fields:** `id`, `tenant_id`, `deal_id`, `type`, `value`, `computed_amount`, `reason`, `approved_by_user_id`, `applied_at`, `reverses_discount_id`.
**Model-agnostic.**

### 18. CommissionRule — MVP
**Purpose:** The configurable definition of how any commission is computed and when it is earned. **Replaces the hardcoded two-leg assumption.**
**Key fields:** `id`, `tenant_id`, `direction` (`inbound`|`outbound`), `scope` (`tenant`|`developer`|`project`|`deal`), `scope_id`, `participant_role` (outbound only: `agent`|`team_leader`|`referrer`|`other`), `basis`, `calc_type` (`percent`|`fixed`|`tiered`), `value`, `tiers JSONB`, `trigger`, `effective_from`, `effective_to`, `is_active`.
**Full specification:** `26_Commission_Rules_and_Splits.md`.
**Changed in rev 2:** generalised from the developer/agent pair to a direction-and-scope model with configurable basis and trigger.

### 19. Commission — MVP
**Purpose:** One commission entitlement on one deal, in one direction. **Replaces `DeveloperCommission` and `AgentCommissionPayout`.**
**Key fields:** `id`, `tenant_id`, `deal_id`, `direction`, `counterparty_type` (`developer`|`partner`|`user`), `counterparty_id`, `participant_role`, `rule_id`, `rule_snapshot JSONB`, `basis_amount`, `rate_applied`, `expected_amount`, `settled_amount`, `state`, `trigger_met_at`, `due_date`.
**Relationships:** many per Deal — zero or one inbound, zero or more outbound.
**Lifecycle:** `18`§8 — shared skeleton with direction-specific states.
**Snapshotting:** the resolved rule is copied into `rule_snapshot` at deal activation so later rule changes never alter historical commissions. This is a hard requirement, not an optimisation.
**Model note:** `own_inventory` deals produce outbound rows only; `brokered_inventory` deals produce one inbound row plus outbound rows.

### 20. CommissionAdjustment — MVP
**Purpose:** Clawbacks and corrections as offsetting rows rather than edits.
**Key fields:** `id`, `tenant_id`, `commission_id`, `amount`, `reason`, `created_by_user_id`, `created_at`.

### 21. Cancellation — P2
**Purpose:** Records termination of a Deal and its consequences, **model-aware**.
**Key fields:** `id`, `tenant_id`, `deal_id`, `requested_at`, `reason`, `initiated_by` (`tenant`|`developer`|`customer`), `grace_period_end`, `total_paid_at_cancellation`, `refund_policy_ref`, `computed_refund_amount`, `approved_by_user_id`, `status`.
**Lifecycle:** `18`§10.
**Changed in rev 2:** added `initiated_by`; refund computation applies to `own_inventory` only (in `brokered_inventory` the developer decides and the tenant records the outcome — `27`§5).

### 22. UnitTransfer — Future
Unchanged from revision 1. Not built until requested.

### 23. Supporting entities — MVP (unchanged)
`Activity`, `Task`, `Document`, `Notification`, `AuditEvent`, `ImportJob`, `UnitStatusHistory`.

---

## Part C — Phase summary

| Phase | Entities |
|---|---|
| **MVP** | Tenant, Branch, User, Lead, Customer, Developer *(optional)*, Project, Phase, Unit, Reservation, Deal, PaymentPlanTemplate, CustomerPaymentPlan, Installment, Payment, PaymentAllocation, Discount, CommissionRule, Commission, CommissionAdjustment + supporting entities |
| **P2** | Cancellation, PostDatedCheque, plan versioning, tiered commission rules, commission tranche schedules |
| **Future** | UnitTransfer, multi-currency, developer collection reconciliation |

---

## Part D — Summary of changes from revision 1

| Change | Reason |
|---|---|
| `Developer` optional, required only for brokered projects | Requested architecture change; a tenant selling its own stock has no developer |
| `Project.commercial_model` added | The simple configurable field that replaces a would-be abstraction (`25`§3) |
| `Deal.commercial_model` denormalised | Financial rules must not shift if a project is edited |
| `DeveloperCommission` + `AgentCommissionPayout` → unified `Commission` | Two-leg model was valid only for brokered inventory; direction field generalises it |
| `CommissionRule` gains direction, scope, configurable basis and trigger | Basis and timing are now explicitly configurable, not hardcoded |
| `Deal.collection_owner` removed | Redundant once the commercial model is on the deal |
| `Payment.collected_by` removed | Payments exist only where the tenant collects |
| `Deal.down_payment_confirmed_at` added | Supports the confirmed inbound trigger without building reconciliation (`25`§4) |
| `Cancellation.initiated_by` added | Cancellation authority differs by model |
