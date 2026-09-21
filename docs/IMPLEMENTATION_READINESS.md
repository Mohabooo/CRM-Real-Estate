# IMPLEMENTATION READINESS

**Gate date:** September 2026
**Scope reviewed:** documents `15`–`27` and their latest revisions, validated together for internal consistency.
**Outcome:**

# ✅ IMPLEMENTATION READY

**No true blockers remain.** Two genuine contradictions and one genuine design gap were found and resolved within this gate; six ambiguities were closed as implementation decisions. All resolutions are patched into the source documents, so the specification set is now internally consistent.

---

## 1. Readiness status

| Area | Status |
|---|---|
| Commercial models (`own_inventory` / `brokered_inventory`) | Settled |
| Project-level `commercial_model`, optional Developer | Settled, DB-enforced (C10) |
| Deal lifecycle | Settled |
| Payment plan → installment → payment → allocation | Settled |
| Outstanding / Overdue / Forecast | Settled, model-scoped |
| Commission rules, participants, triggers | Settled; one gap closed (`peer_commission`) |
| Reversal instead of deletion | Settled |
| Eight commercial-model policy points | Settled, with build-breaking enforcement |
| Technical architecture | Produced — `28_Technical_Architecture.md` |
| Test specification | Produced — `29_Financial_and_Commission_Test_Specification.md` |

---

## 2. Findings and classification

### Contradictions found — both resolved

**F-01 · Commission-rule absence: warn or block?** — *was a real contradiction*
`17` R-COMM-3, `19` F5-A4 and `26`§3 said activation proceeds with a warning when no rule resolves. `20` E3-S2 said a brokered deal cannot activate without an inbound rule. Both could not be true.
**Resolved by direction:** a **brokered** deal with no inbound rule is **blocked** — its entire commercial purpose is the commission, so activating one is a misconfiguration that produces a deal nobody will ever be paid for. **Outbound** absence **warns and proceeds** — a salaried team with no commission scheme is legitimate. Patched into `17`, `19`, `26`.
*Classification: IMPLEMENTATION DECISION, with a product-behaviour edge. Flagged for your override if you disagree — it is the one resolution in this gate that changes what a user experiences.*

**F-02 · Rounding: half-up or floor?** — *was a real contradiction*
`17`'s conventions and `22`§8 stated half-up universally, including the base installment; R-INST-1 specified floor. Half-up there can produce a negative remainder, breaking the invariant the generator asserts.
**Resolved:** half-up everywhere **except** the base installment, which floors deliberately so the remainder is always non-negative and lands on the final installment. Patched into `17` and `22`.
*Classification: IMPLEMENTATION DECISION.*

### Gap found — resolved

**F-04 · No basis for peer-derived commission** — *was a real gap*
The five configured bases could express "the team leader gets 10% of what the **company** earns" but not "10% of what the **agent** earns." Both are real arrangements and they produce materially different numbers (4,275 vs 8,550 on the canonical deal). Without the basis, users would hand-convert one into the other — arithmetic that breaks silently the moment the agent's rate changes.
**Resolved:** added the `peer_commission` basis with acyclic dependency ordering, cycle rejection and a maximum depth of 3. Patched into `26`§4; determinism rules in `28`§9.
*Classification: IMPLEMENTATION DECISION (capability already implied by "configurable splits"; this makes it expressible).*

### Ambiguities closed — IMPLEMENTATION DECISIONS

| # | Ambiguity | Decision |
|---|---|---|
| F-03 | `down_payment_satisfied` undefined when the down payment is zero — and 0%-down plans are documented in the Egyptian market | Satisfied from activation in both models; BRK confirmation action not offered. Added as R-DP-3. Without this, a 0%-down brokered deal could never accrue inbound commission. |
| F-05 | Trigger applicability per direction and model was unconstrained — an own-inventory agent could be configured to wait out an 8-year schedule | Trigger × direction × model validity matrix, validated at rule save (`28`§9.6) |
| F-06 | `settled_amount` exists but no `partially_settled` state | Partial settlement is **derived**, not stored — consistent with the existing rule that derived states are never persisted |
| F-07 | Brokered plan closure leaves installments `scheduled` | They stay `scheduled`. They were never obligations to the tenant, and R-SCOPE-2 already excludes them. Voiding would imply cancellation, a different fact. |
| F-08 | A tenant-scoped rule can be valid for one model and not another | Skip the entitlement, record a warning on the activation result — never fail the deal, never silently produce a wrong figure |
| F-09 | Reservation deposit vs. down-payment confirmation could be conflated | Distinct fields, distinct meanings; a brokered reservation deposit does **not** satisfy R-DP-1 |

### CONFIGURATION — not blockers

Commission rates, bases, triggers, participants and splits; discount thresholds; grace days; aging bucket boundaries; reservation expiry and extension limits; clawback approval; deal-completion definition for brokered deals. All catalogued in `15` Category 2 with defaults where a safe one exists. Deliberately **not** defaulted: commission rates and splits, because research found no published Egyptian convention and an invented default would be read as a recommendation.

### P2 / FUTURE — untouched

Cancellation refund engine, plan versioning, automated commission triggers, tiered rules, PDC ledger, risk-adjusted forecast, unit transfer, multi-currency, developer collection reconciliation.

### NO ACTION

`own_inventory` / `brokered_inventory` semantics, Deal lifecycle, the payment-plan/installment/payment/allocation model, reversal-over-deletion, and the eight policy points were all reviewed and found internally consistent across `15`–`27`. A mechanical scan for stale terminology (`DeveloperCommission`, `AgentCommissionPayout`, `collected_by`, `collection_owner`) found no live usages — only change-log references describing what was replaced.

---

## 3. Architecture decisions

| # | Decision | Why |
|---|---|---|
| A14 | **Java 21 + Spring Boot 3 + PostgreSQL 16** | `BigDecimal` is native and exact. The dominant risk in this product is money arithmetic, not throughput; a language where exact decimals are a standard type removes a whole defect class. TypeScript/NestJS was the alternative and was rejected because JavaScript has no native exact decimal — every money value would depend on a library boundary being respected perfectly, forever. |
| A15 | **ArchUnit rules break the build** | Model containment is worthless if it depends on reviewer vigilance |
| A16 | **Financial calculators are pure functions** | Every rule in `17` becomes a unit test with no database and no framework |
| A17 | **`Money` type; raw `BigDecimal` banned in domain signatures** | The rounding policy cannot be bypassed by accident |
| A18 | **Commission entitlements are independent claims, not pool slices** | Eliminates the entire "distribute the remainder" class of ambiguity |
| A19 | **`peer_commission` with acyclic ordering** | Expresses a real arrangement that would otherwise be silently mis-modelled |
| A20 | **C15 uniqueness on (deal, direction, role, counterparty)** | Double-counting becomes structurally impossible, not merely unlikely |
| A21 | **Property-based schedule tests written before the generator** | Highest-value test in the project; most valuable before the code exists |

Carried forward unchanged: modular monolith, financial-core module boundary, derived-never-stored states, reversal over deletion, DB-level one-deal-per-unit, two-principal approval for payment void and cancellation, AI with no write access to the financial core.

**Model containment, concretely.** Eight policy interfaces, two implementations each, one resolver, one package. An ArchUnit rule fails the build if `CommercialModel` is referenced anywhere outside it, and a test asserts the interface count is exactly eight — so adding a ninth requires deliberately deleting an assertion, which surfaces in review.

---

## 4. Database and domain decisions

- Schema per `22` (revision 2), with the rounding clarification patched at this gate.
- **Fifteen constraints** must exist before any financial code is written: C1–C14 from `22` plus **C15** (commission participant uniqueness) added here. Constraints ship in the same migration as the table they protect — never "added later."
- Two independent tenant-isolation layers: Postgres RLS plus repository scoping. Composite foreign keys including `tenant_id` in the financial path make cross-tenant references structurally impossible.
- `NUMERIC(18,2)` amounts, `NUMERIC(18,4)` rates, `DATE` for due/payment dates, `TIMESTAMPTZ` for events.
- Flyway forward-only migrations; financial-table migrations carry a CI approval gate.
- Deterministic commission computation: topological sort over the dependency graph, cycles rejected, depth capped at 3, `settled_amount` never an input to another calculation.

---

## 5. Testing gates

Nine gates define shippable; a build failing any of them is producing numbers nobody should act on.

1. **FIN-001** — canonical fixture reproduces exactly, to the cent.
2. **FIN-018 [property]** — `down + delivery + Σ installments == net_value` for any valid plan configuration.
3. **FIN-066 [property]** — payment applied then voided returns the system to its exact prior state.
4. **FIN-080 [property]** — `Σ expected = Σ allocated + Σ outstanding`, any as-of date.
5. **COM-040 [property]** — no entitlement is ever computed twice.
6. **CON-001 / CON-002** — no double-reservation, no double-sale, verified against real PostgreSQL.
7. **REC-001** — every dashboard headline equals its drill-down total.
8. **ArchUnit** — model containment and module boundaries hold.
9. **AUD-001** — every transition marked audited in `18` writes a record.

Full case list: `29_Financial_and_Commission_Test_Specification.md` (roughly 140 specified cases across financial, commission, model, concurrency, reconciliation and audit).

---

## 6. First implementation epics, in order

Dependency-driven, from `24` revision 2. The first two epics are unblocked by anything.

| # | Epic | Contents | Exit criterion |
|---|---|---|---|
| **0** | **Foundation** | Repo structure, CI, Flyway baseline, `Money` type, `ScheduleDateCalculator`, ArchUnit rules, error catalogue | Empty deployable app; ArchUnit and date/money unit tests green |
| **1** | **Tenancy, identity, audit** | Tenants, branches, users, roles, invitations, RLS, branch scoping, audit infrastructure | Cross-tenant leakage suite passes; audit records immutable |
| **2** | **Commercial models + inventory** | `commercial_model` on projects with C10, developers, projects, phases, units, status lifecycle, C1/C2, CSV import | Both model types creatable; developer requirement enforced both ways; concurrency test proves no double-reservation |
| **3** | **CRM core** *(parallelizable with 2)* | Leads, assignment, activities, stale queues, customers, dedupe warnings | Lead-to-customer conversion works end to end |
| **4** | **Deals + payment plans** | Deal draft/activate, discounts with approval, templates, instances, **schedule generator** | FIN-001 and FIN-018 pass; activation is atomic (CON-003) |
| **5** | **Payments + collections** | Payment recording, allocation engine, clear/bounce/void with reversal, the five collection figures | FIN-066 and FIN-080 pass; MOD-003 rejects brokered payments |
| **6** | **Commission engine** | Rule configuration, resolution, snapshotting, entitlements both directions, peer ordering, overrides, clawback | COM-040 passes; all four reference configurations reproduce correctly |
| **7** | **Overdue + notifications** | Overdue queue, aging, grace, thresholds, reminders | FIN-070–FIN-080 pass; no brokered deal ever appears overdue |
| **8** | **Dashboards + reporting** | Model-aware CEO dashboard, forecasts, exports | REC-001 passes; mixed portfolio never sums cash with commission |

**Note on Epic 4:** write the property-based schedule tests (FIN-018) *before* the generator. It is the single highest-value test in the project and it is most useful while the code it constrains does not yet exist.

---

## 7. One caveat worth your attention

F-01's resolution — blocking activation of a brokered deal that has no inbound commission rule — is the one decision in this gate that changes what a user experiences rather than merely settling how something is built. I chose blocking because a brokered deal with no commission rule is almost certainly a setup error, and the alternative produces deals that quietly earn nothing. If your view is that a brokerage should be able to log such a deal anyway (a favour, a strategic placement, a developer relationship deal), say so and it becomes a warning instead — a one-line change now, and a more awkward one later.

Everything else in this gate either settled a contradiction with no behavioural choice attached, or closed an ambiguity in the direction the existing documents already implied.

---

**Status: IMPLEMENTATION READY. Awaiting instruction to begin implementation.**
