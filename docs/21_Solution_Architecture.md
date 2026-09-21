# 21 — Solution Architecture

**Revision 2 — September 2026.** Revised for the multi-commercial-model direction. The architectural style is unchanged; what is added is an explicit **commercial-model policy layer** (§2a) and a generalised commission module. Everything else in this document survives the revision intact.

**Subject:** The technical architecture for the MVP and its immediate successors. Direction is inherited from BRD §10 (modular monolith, shared Postgres, S3-compatible storage) and confirmed — not revisited — because nothing in `01`–`27` gives a concrete reason to change it.

---

## 1. Architectural style: modular monolith (confirmed)

**Decision:** one deployable application with strictly separated internal domain modules. No microservices.

**Why this remains right for this system:**
- The target customer is an SMB brokerage (BRD §3.6, ~5–150 users). Deal volume is in the hundreds-to-thousands per tenant per year, not millions.
- The money model is **transactionally dense**: activating a deal must atomically write a deal, a plan, N installments, two commission records and a unit status change (`18`§11). Across service boundaries this becomes a distributed transaction or a saga — a large amount of complexity purchased for no benefit at this scale, and a direct threat to the financial-integrity requirement (`07` N4).
- Team size and time-to-pilot favour one codebase, one migration path, one deployment.

**When to revisit:** if reporting load begins to degrade transactional performance at multi-tenant scale, the first move is a read replica and then a reporting store — not service decomposition. Extraction of a service should only be considered for a module with a genuinely different scaling or availability profile (realistically: notifications, or a future WhatsApp gateway).

---

## 2. Domain modules

Module boundaries follow the domain model (`16`), extending BRD §10's list with the money modules. Each module owns its tables and exposes an internal service interface; cross-module access goes through those interfaces, never direct table reads.

| Module | Owns | Key dependencies |
|---|---|---|
| `tenancy` | Tenant, TenantSettings, Branch | — |
| `identity` | User, Membership, Role, invitations, sessions | tenancy |
| `crm` | Lead, Customer, Activity, Task | identity, tenancy |
| `inventory` | Developer, Project, Phase, Unit, UnitStatusHistory | tenancy |
| `reservations` | Reservation | inventory, crm |
| `deals` | Deal, Discount, deal_customers | inventory, crm, reservations |
| `payment_plans` | PaymentPlanTemplate, CustomerPaymentPlan, Installment, **the generation engine** | deals, inventory |
| `collections` | Payment, PaymentAllocation, allocation engine, (P2) PostDatedCheque | payment_plans, deals |
| `commissions` | CommissionRule, Commission (both directions), CommissionAdjustment, rule resolution and snapshotting | deals, collections, identity |
| `reporting` | Read-side aggregates for dashboards and exports | all of the above (read-only) |
| `documents` | Document, storage references | tenancy |
| `notifications` | Notification, preferences, dispatch | all (event consumers) |
| `integrations` | Import/export, future external connectors | all |
| `ai` | AiJob, prompt templates, usage logs | crm, deals |
| `audit` | AuditEvent | all (write-only sink) |
| `billing` | Vendor's own SaaS subscription (BRD §5.22) | tenancy |

**Critical boundary rule:** `payment_plans`, `collections` and `commissions` form the **financial core**. Only these modules may write to installments, payments, allocations or commission rows. `reporting` reads them but never writes. Any feature needing to move money goes through the financial core's service layer, which is where the invariants in `17` are enforced — this is what prevents a future "quick fix" endpoint from writing a payment row that bypasses allocation logic.

---

## 2a. The commercial-model policy layer

The product serves two commercial models (`25`). The architectural risk is obvious: `if (model == brokered)` scattered through controllers, services and views, multiplying every future change by two and guaranteeing that some code path eventually forgets to branch.

**The rule:** the core engine is model-agnostic. The commercial model is consulted only at named **policy points**, each a small strategy object resolved from `deal.commercial_model`.

| Policy | Owning module | Resolves |
|---|---|---|
| `InventoryOwnershipPolicy` | `inventory` | Is a developer required? Who is the seller of record? |
| `CollectionPolicy` | `collections` | May payments be recorded for this deal? (R-PAY-0) |
| `CollectionMetricsPolicy` | `reporting` | Do outstanding/overdue/aging/cash-forecast apply? (R-SCOPE-2, R-BRK-1) |
| `DownPaymentSatisfactionPolicy` | `deals` | How is `down_payment_satisfied` determined? (R-DP-1) |
| `ExternalCommissionPolicy` | `commissions` | Does an inbound entitlement exist, and on what terms? |
| `InternalCommissionPolicy` | `commissions` | Which participants, what basis, which trigger? |
| `CancellationPolicy` | `deals` | Who decides, is a refund computed, what is clawed back? |
| `DashboardPolicy` | `reporting` | Which financial panels apply? |

**Design constraints on the policy layer**
1. **Exactly eight policy points.** Adding a ninth is a design decision requiring justification, not a routine change — the count is the control on model-branching spreading.
2. **Policies are resolved from the Deal, not the Project.** `commercial_model` is denormalised onto the deal at creation (`16`§11) so a project edit can never retroactively change how an existing deal behaves.
3. **Policies decide; they do not compute.** `CollectionMetricsPolicy` answers "do these metrics apply?" — it does not calculate overdue. The calculation lives in one place and runs unchanged.
4. **Absence is explicit.** Where a policy says a capability does not apply, the API returns a documented "not applicable for this commercial model" state rather than zeros or empty collections. Zeros are read as facts (R-BRK-1).
5. **Every policy is independently testable** with a deal fixture per model — this is how the two-model matrix stays manageable in the test suite.

**Ordering constraint in the commission module.** On deal activation, inbound entitlements are computed before outbound, because outbound rules may use `inbound_commission` as their basis (R-COMM-2). This ordering lives in one service method in `commissions`, not in the caller.

---

## 3. Backend

- **Runtime:** a mature, transactionally-safe stack with strong decimal support and a solid migration story. Language choice is open; the non-negotiable is **exact decimal arithmetic** in the financial path (no floats, `22`§8) and first-class database transactions.
- **Layering per module:** HTTP controller → application service (transaction boundary) → domain logic → repository. Financial invariants (R-PLAN-4, allocation limits) live in domain logic and are additionally enforced by database constraints (`22`§7) — belt and braces, because an invariant enforced only in application code will eventually be bypassed.
- **Transaction rule:** one business operation = one database transaction. Deal activation, payment allocation, payment voiding and (P2) cancellation each commit atomically or not at all. Side effects that cannot be transactional (emails, webhooks) are enqueued inside the transaction and dispatched after commit.
- **Idempotency:** payment recording and import operations accept an idempotency key so a retried request cannot double-record money.

---

## 4. Frontend

- **Responsive web, RTL-ready** (BRD §10, §1.3). No native apps in MVP.
- **Role-driven layouts:** agent (my leads, my deals, quick log), ops/finance (payment entry, overdue queue), manager/owner (dashboards). These are genuinely different applications of the same data — building one generic UI with permission-hidden widgets produces a poor experience for all three.
- **Arabic/English** with locale-aware number and date formatting; amounts always displayed with explicit currency (`07` N8).
- **Money UX rules:** never display a money figure without its label distinguishing expected/collected/outstanding/overdue; always allow drill-through from an aggregate to its rows; show the computation (e.g. gross − discount = net) rather than just the result on deal screens.

---

## 5. Database and multi-tenancy

- **PostgreSQL**, shared database with `tenant_id` on every table (BRD §10 confirmed).
- **Isolation enforcement:** row-level security policies keyed on a session-scoped tenant claim, plus application-layer scoping. Two independent mechanisms, because cross-tenant leakage is the single worst failure mode for a multi-tenant SaaS.
- **Dedicated database tier per tenant** remains Future/Enterprise (BRD §10), unchanged.
- Full detail in `22_Database_Design.md`.

---

## 6. Authentication and authorization

- **Authentication:** email + password with standard hashing, session or token based, password reset, invitation flow. SSO is Future/Enterprise (BRD §14).
- **Authorization:** fixed roles (BRD §4) evaluated **server-side on every request**. Three dimensions: role (what actions), branch scope (whose records), and field-level restrictions (national ID, commission figures).
- **Financial actions require elevated roles**, and two actions require two roles: voiding a payment (FIN + BM) and approving a cancellation (BM + OWN). This is a deliberate separation-of-duties control appropriate to a money system.

---

## 7. File storage

- S3-compatible object storage with signed, time-limited URLs (BRD §10).
- Financial proof documents (receipts, statements, later PDC images) are stored with stricter access control than general documents (`07` N7) and every download is audited (BRD §5.20 precedent).
- Virus/malware scanning: P2+ (BRD §5.10).

---

## 8. Background jobs

A queue with retries and dead-lettering. MVP jobs:
- Reservation expiry sweep (`18`§3).
- Notification dispatch and digests (F13).
- Scheduled report/digest emails.
- Import processing for large files.

**Explicitly not a job:** computing overdue status or aging buckets. These are derived on read (`18`§6) precisely so that a failed job can never silently corrupt collections reporting.

---

## 9. Audit

- Append-only `audit_events` table, written by every module through the `audit` module.
- Records actor, tenant, entity type/id, action, before/after snapshot (for financial rows), reason, timestamp, request correlation id.
- Immutable: no update/delete path exists in the application; database permissions reinforce this.
- Retention and export are tenant-configurable (PDPL-aware posture, BRD §7.5).

---

## 10. Reporting

- **MVP:** direct queries against the transactional database with appropriate indexes (`22`§6), served by the `reporting` module. Pilot data volumes do not justify anything more (`07` N5, BRD §10 "early non-goals").
- **P2 if needed:** materialized views or nightly aggregate snapshots for dashboard figures, with the rule that **any materialized figure must be re-derivable from source** and reconciled automatically — a cached number that can silently drift from its source is worse than a slow query.
- **Future:** read replica, then a warehouse, only if scale demands it.

---

## 11. AI integration

Unchanged from BRD §5.14/§7: assistive only, human-in-the-loop, provider adapter behind an interface, async jobs, tenant-configurable limits, usage logging.

**One architectural constraint added by this phase:** AI must never write to the financial core. It may summarize a customer's payment situation, draft a collection follow-up message, or explain a dashboard movement — it may not record payments, alter schedules, or change commission states. This is enforced by module boundaries (the `ai` module has no write path into `payment_plans`, `collections` or `commissions`), not merely by prompt design.

---

## 12. External integrations

- **MVP:** CSV import/export only (BRD §5.19).
- **P2:** WhatsApp templates, email notifications at scale.
- **Future:** developer inventory feeds, accounting/ERP connectors, payment gateways — all gated on business decisions (`15` C9) and all currently out of scope (`05`§4).
- Integration code lives behind adapter interfaces in `integrations` so that nothing in the financial core depends on an external system's availability.

---

## 13. API boundaries

- Internal: module service interfaces, not shared table access.
- External: REST under `/api/v1/` with tenant scoping (BRD Appendix C), detailed in `23_API_Design.md`.
- Public lead-capture endpoint with anti-spam is MVP-Rec/P2 (BRD Appendix C, unchanged).
- Outbound webhooks: P2+.

---

## 14. Observability and operations

- Structured logs with tenant and correlation ids; error tracking; basic uptime and job-queue monitoring (BRD §10).
- **Financial-specific monitoring:** alert on any detected invariant violation (R-PLAN-4 failures, allocations exceeding payment amounts, orphaned installments). These should page someone, not just log — they indicate data corruption in the one area where corruption is unacceptable.
- Managed hosting and managed Postgres, staging environment, secrets manager, automated backups with tested restore (BRD §10).

---

## 15. Architectural decisions summary

| # | Decision | Rationale |
|---|---|---|
| A1 | Modular monolith, not microservices | Transactional density of the money model; SMB scale; team size |
| A2 | Financial core module boundary | Single enforcement point for money invariants |
| A3 | Derived overdue/aging, never stored | Job failure cannot corrupt collections reporting |
| A4 | Reversal over deletion in the financial path | Auditability; regulatory and dispute defensibility |
| A5 | DB-level enforcement of one-active-deal-per-unit | Concurrency safety for the overselling control |
| A6 | Two-role approval for payment void and cancellation | Separation of duties proportionate to financial risk |
| A7 | AI has no write access to the financial core | Containment of a probabilistic component away from exact money |
| A8 | Reporting reads transactional DB in MVP | Avoids premature warehouse complexity (BRD §10 non-goal) |
| A9 | Commercial model is a field on Project, denormalised to Deal — not a class hierarchy or plugin | Simplest construct that expresses the requirement; supports mixed portfolios; avoids the abstraction the revision explicitly warned against (`25`§3) |
| A10 | Exactly eight named policy points; core engine otherwise model-agnostic | Contains model-branching to a testable, countable surface instead of letting it spread |
| A11 | One `commissions` table with a `direction` field, replacing separate developer/agent tables | Lifecycles are the same shape; avoids duplicating rule resolution, snapshotting and clawback logic; makes "commission position on this deal" one query |
| A12 | Commission rule snapshotted onto the entitlement at activation | Historical defensibility; rule changes must never restate past commissions (R-COMM-4) |
| A13 | Payments rejected — not flagged — on brokered deals | A partially-populated ledger invites people to read totals that mean nothing (R-PAY-0) |
