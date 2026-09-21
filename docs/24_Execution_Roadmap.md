# 24 — Execution Roadmap

**Revision 2 — September 2026.** Revised for the multi-commercial-model direction. The sequence is unchanged; what changed is that **Epic 8 (commission) is no longer blocked** — it builds a configurable engine, and tenant values arrive at configuration time. Commercial-model support is added to Phase 2 and the commission-configuration epic joins Phase 5.

**Subject:** A dependency-aware implementation sequence with the process definitions needed to run it. This validates and adjusts the straw-man sequence proposed in the brief rather than accepting it as given.

---

## 1. Validating the proposed sequence

The proposed order was: Foundation → Auth/Tenant → CRM → Inventory → Reservation → Deal → Payment Plan → Installments → Payments → Collections → Commission → Dashboards → Notifications → AI.

**It is broadly correct**, and the dependency analysis confirms most of it. Four adjustments:

**Adjustment 1 — Inventory should come before CRM, or in parallel.** Deals depend on Inventory, not on the lead funnel. A deal can be created for a walk-in customer with no lead history at all (F3). Inventory is therefore on the critical path to the money model and the lead funnel is not. Running Inventory first (or both in parallel) shortens the time to the first demonstrable end-to-end money flow, which is what this project is actually being judged on.

**Adjustment 2 — Reservation is not on the critical path.** A Deal can be created directly from an available unit (`18`§4). Reservation is valuable and should ship in MVP, but it can be built *after* the Deal→Payment Plan→Installments spine, and doing so removes a dependency from the highest-risk sequence.

**Adjustment 3 — Notifications should start earlier, incrementally.** Overdue notifications (F13) are part of what makes collections useful, not a post-collections add-on. The infrastructure should land alongside Collections, even if the full trigger set arrives later.

**Adjustment 4 — Audit must be built first, not retrofitted.** It appears nowhere in the proposed sequence. Every phase from Inventory onward writes audit records (`18`), and retrofitting audit into an existing financial module means re-touching every write path. It belongs in Foundation.

---

## 2. Dependency graph (epics from `20`)

```
E1 Foundation (tenancy, identity, access, AUDIT)
      │
      ├──────────────┬──────────────────────┐
      ▼              ▼                      ▼
E3 Inventory    E2 CRM core          (Notifications infra)
      │              │                      │
      │              └──────┬───────────────┘
      ▼                     ▼
E4 Reservations   ┌──► E5 Deals + Payment Plans  ◄── (needs E3 + customers from E2)
      │           │         │
      └───────────┘         ▼
                   E6 Payments + Collections
                            │
                  ┌─────────┴──────────┐
                  ▼                    ▼
            E7 Overdue mgmt      E8 Commission engine (no longer blocked)
                  │                    │
                  └─────────┬──────────┘
                            ▼
                   E9 CEO Dashboard + Reporting
                            │
                            ▼
                   E10 Notifications / import / documents
```

**Critical path:** E1 → E3 → E5 → E6 → E9. Everything else can be parallelized or deferred without delaying the demonstrable outcome.

**No hard external dependencies remain.** Revision 1 gated E8 on four commission questions. Those resolved into product capabilities (designed in `26`) and tenant configuration values (`15` Category 2), so E8 builds on the same schedule as everything else. What it still needs before *pilot sign-off* — not before development — is one real tenant's actual commission arrangement to configure and verify against.

**One sequencing note added in rev 2:** commercial-model support (E3-S1/S2) must land with Inventory in Phase 2, not later. Every downstream financial behaviour reads the model, so retrofitting it after deals exist would mean backfilling `commercial_model` onto live deals — exactly the kind of financial-data migration `22`§10 warns about.

---

## 3. Development phases

### Phase 0 — Setup (short)
Repository, CI, environments (dev/staging), migration tooling, error tracking, coding standards, decimal-handling utilities with their own test suite, and the schedule date-arithmetic utility (R-INST-5/6) built and tested in isolation before anything depends on it.

**Exit:** a deployable empty application with CI green and a staging environment.

### Phase 1 — Foundation
E1: tenancy, RLS, identity, roles, branch scoping, invitations, **audit infrastructure**, cross-tenant leakage tests.

**Exit:** users can be invited, log in, and see only their tenant's (empty) data; audit records are written and immutable; the cross-tenant test suite passes.

### Phase 2 — Commercial models + Inventory (+ CRM in parallel if capacity allows)
E3: **commercial model on projects with the conditional-developer constraint (C10)**, developers, projects, phases, units, status lifecycle with DB-level constraints (C1/C2), CSV import, browse/filter.
E2 (parallel): leads, activities, assignment, stale queues, customers.

**Exit:** both an own-inventory and a brokered project can be created, the developer requirement is enforced correctly in each direction, inventory loads from a price list, and a concurrency test proves no double-reservation.

### Phase 3 — Deals and payment plans (the heart)
E5: deal draft/activate, discounts with approval threshold, payment plan templates, plan instantiation, installment generation engine, preview endpoint.

**Exit:** the `17`§5 worked example reproduces to the cent through the UI; a deal activates atomically and locks its unit; schedules freeze after activation.

### Phase 4 — Payments and collections
E6: payment recording, auto and manual allocation, clearing/bouncing/voiding with full reversal, the five collection figures, drill-through, CSV export.
Notifications infrastructure lands here.

**Exit:** a full payment lifecycle including a void reconciles exactly back to its pre-payment state; collection figures match drill-down in every tested scope.

### Phase 5 — Overdue and commission
E7: overdue queue, aging, grace periods, overdue notifications (own-inventory only).
E8: commission rule configuration, unified entitlements in both directions, rule resolution and snapshotting, state transitions, overrides, clawback.

**Exit:** overdue figures correct under partial payments and grace periods; the commission engine reproduces at least four distinct configurations (agent-only; agent+TL; agent+TL+referrer; external-partner payee) and reconciles per deal against hand-calculated expectations using a real tenant's rates.

### Phase 6 — Dashboards and reporting
E9: CEO dashboard, period switching, forecast, by-project breakdowns, exports, performance tuning.

**Exit:** every headline figure equals its drill-down total; dashboard performs acceptably at pilot volume.

### Phase 7 — Pilot hardening
Data import for the pilot brokerage's real history, parallel-run reconciliation against their spreadsheets, bug fixing, training material, security review.

**Exit:** pilot exit criteria in `20` Part C met.

---

## 4. Definition of Ready

A story may enter development only when:
1. Acceptance criteria are written and testable (all stories in `20` already have these).
2. Any `15` D-question it depends on is **answered**, or the story is explicitly scoped to exclude the blocked part.
3. Its business rules are referenced by rule ID from `17` (no story invents a formula).
4. Its state transitions are covered in `18` (no story invents a state).
5. Permissions and audit requirements are stated.
6. Test data / fixtures needed are identified — for financial stories, a worked numeric example is mandatory.
7. UI stories have a wireframe or an agreed textual layout.

## 5. Definition of Done

A story is done only when:
1. Acceptance criteria pass, demonstrated on staging.
2. Automated tests exist: unit tests for rules, integration tests for flows, and for any financial story a test asserting the relevant invariant (R-PLAN-4, allocation bounds, reversal symmetry).
3. Database constraints backing the story's invariants are in place (not application-only enforcement).
4. Audit records are produced for every audited action in the story.
5. Permissions verified for at least one authorized and one unauthorized role.
6. Cross-tenant isolation verified for any new endpoint.
7. Migrations are reversible or have a documented forward fix.
8. Arabic/English labels present for user-facing text.
9. Code reviewed, with financial-path changes reviewed by a second person as a hard rule.
10. Documentation updated where the story changes a rule, state or contract.

---

## 6. Testing strategy

**Unit tests** — every formula in `17` with boundary cases: zero discount, 100%-down plans, single-installment plans, rounding remainders, end-of-month date generation across leap years.

**Property-based tests** for the generation engine — for any valid combination of net value, down payment %, delivery %, count and frequency, assert that `down + delivery + Σ installments = net` exactly. This is the highest-value test in the project and should be written before the engine itself.

**Integration tests** — full flows: lead→reservation→deal→plan→payment→collection figures; payment void and full reversal; concurrent reservation attempts; concurrent deal activation on the same unit.

**Reconciliation tests** — for a seeded dataset, assert that every dashboard aggregate equals the sum of its drill-down rows. Run in CI, not just manually.

**Security tests** — cross-tenant access attempts on every endpoint; role-permission matrix; field-level restriction on national ID and commission.

**Performance tests** — dashboard and collections queries at 10× expected pilot volume.

**Manual/exploratory** — the money screens specifically, with a finance-literate tester who will notice that "outstanding" and "total remaining" are being confused long before a test would.

---

## 7. Migration and import strategy

The pilot brokerage's existing data lives in spreadsheets and, for deals, likely in developer portals and paper contracts.

1. **Inventory first** — import projects and units from developer price lists. Low risk, immediately useful.
2. **Customers and leads** — import with dedupe warnings; accept that the incoming data is messy.
3. **Active deals** — the hard part. Each historical deal needs: unit, customer, agreed price, plan terms, and **payments already made**. Recommended approach: enter the deal with its original terms, generate the full original schedule, then import historical payments and let allocation reconstruct the current position. This yields correct outstanding/overdue figures without hand-entering derived state.
4. **Reconciliation checkpoint** — before go-live, the imported totals (total contracted, total collected, total outstanding) must be reconciled against the brokerage's own numbers, with every discrepancy explained. A discrepancy that cannot be explained is a migration defect, not a rounding difference.
5. **Commission history** — import only what is needed for open commissions; closed historical commissions can stay out of the system.
6. **Parallel run** — the brokerage keeps its spreadsheet for one full collection cycle while the system runs alongside. Divergences are investigated, not averaged.

---

## 8. UAT strategy

**Who:** the brokerage's owner/CEO (dashboards), a finance/ops user (payments, collections, overdue), one sales agent and one branch manager (leads, deals, reservations).

**Structure:** scenario-based, not feature-based. Each UAT scenario is a real business situation end to end — e.g. "a customer buys a 3M unit at 5% discount on a 10%+8yr plan, pays the down payment and first two installments, misses the third, then pays it late; verify every figure the CEO sees at each step."

**Financial UAT must include:** at least one void/correction scenario, one partial payment, one overpayment, one overdue-then-cured case, and a commission walkthrough using the brokerage's real rates on a real deal.

**Sign-off criterion:** the finance user can reproduce the system's outstanding and overdue figures by hand for a sample of five deals. If they cannot, the system is not trusted regardless of whether it is correct.

---

## 9. Deployment strategy

- Managed hosting and managed Postgres (BRD §10), staging mirroring production configuration.
- Migrations run as a distinct, reviewable step; migrations touching financial tables run with an explicit approval gate.
- Automated backups with a **tested restore** — restore is verified in staging before go-live, not assumed.
- Release cadence: continuous to staging, deliberate to production during the pilot, with the brokerage told in advance of anything touching money screens.
- Rollback plan per release; for financial migrations, forward-fix is preferred over rollback once data has been written.
- Monitoring alerts for invariant violations page a human (`21`§14).

---

## 10. Risk register (execution)

| Risk | Impact | Mitigation |
|---|---|---|
| Commercial-model branching spreads through the codebase | Every future change doubles; some path forgets to branch | Exactly eight named policy points (`21`§2a); model-branching outside them is a review failure |
| A pilot tenant's commission arrangement needs a basis or trigger the engine lacks | Epic 8 rework late in the build | Validate the engine against one real tenant's scheme during Phase 3, well before Phase 5 builds it |
| Model-specific behaviour untested for the model the pilot doesn't use | Latent defects ship for the second model | Test fixtures per model from Phase 2; both models exercised in CI regardless of pilot type |
| Installment generation edge cases discovered late | Rework in the most sensitive code | Property-based tests written before the engine; worked example as a fixture from day one |
| Migration of historical deals proves harder than expected | Pilot delayed | Reconstruct via original schedule + payment import (§7.3) rather than hand-entering derived state; start with one project, not the whole book |
| Scope creep toward ERP features during build | MVP never ships | `mvp-scope-discipline-review`; the "explicitly out of scope" list in `20` is a decision, not a backlog |
| Developer-collected payment data arrives too slowly to be useful (D3) | "Collected" figures stale, collection-gated commission impossible | Confirm D3 early; if data is monthly, design the dashboard to label data freshness rather than implying real-time accuracy |
| Dashboard performance at scale | Poor CEO experience, the flagship feature feels slow | Indexes designed up front (`22`§6); performance test at 10× volume in Phase 6; materialization path defined (`21`§10) if needed |
