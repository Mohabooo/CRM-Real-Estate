# 28 — Technical Architecture

**Status:** Implementation-ready. Produced at the September 2026 implementation gate. Sources of truth: `15`–`27`. Where this document specifies *how*, `16`–`18` and `25`–`27` remain the specification of *what*.

**Scope discipline.** This architecture deliberately contains **no** legal-receipt engine, developer collection reconciliation, PDC ledger, bank reconciliation, tax/GL engine, or cancellation refund engine. Those are out of MVP by decision (`20` Part A) and nothing here creates a foothold for them.

---

## 1. Stack

| Layer | Choice | Rationale |
|---|---|---|
| Language/runtime | **Java 21** | `BigDecimal` is a first-class, exact decimal type in the standard library. The single largest correctness risk in this product is money arithmetic; a language where exact decimals are native rather than a library concern removes an entire class of defect. Mature transaction semantics, and the team's existing MuleSoft/enterprise-integration background transfers directly. |
| Framework | **Spring Boot 3.x** | Declarative transactions (`@Transactional`) matching the "one business operation = one transaction" rule; mature validation, security and testing stories; modular-monolith friendly. |
| Database | **PostgreSQL 16** | Already committed in BRD §10. Exact `NUMERIC`, partial unique indexes, exclusion constraints, row-level security — every integrity mechanism in `22` depends on Postgres specifically. |
| Persistence | **Spring Data JPA + jOOQ for reporting** | JPA for transactional aggregates; jOOQ (or plain SQL) for the reporting/aggregation queries, where JPA obscures the SQL that needs tuning. |
| Migrations | **Flyway** | Forward-only versioned SQL, reviewable as SQL rather than generated diffs — required for the financial-table review gate in `24`§9. |
| API | **REST/JSON**, OpenAPI-generated docs | Contract already specified in `23`. |
| Frontend | **React 18 + TypeScript + Vite** | Responsive web with RTL; the role-driven layouts in `21`§4 are three distinct app shells over one API. |
| UI kit | **MUI v5** (RTL-capable) | Native RTL support and locale-aware formatting — a hard requirement (`07` N8), not a preference. |
| Testing | JUnit 5, AssertJ, Testcontainers, jqwik (property-based), ArchUnit | See §14. ArchUnit is load-bearing here, not optional — it enforces §3. |
| Jobs | Spring Scheduling + a DB-backed queue | Only four MVP jobs (`21`§8); a broker would be premature. |

**Alternative considered:** TypeScript/NestJS. Rejected as primary for one reason — JavaScript has no native exact decimal, so every money value depends on a library boundary being respected perfectly, forever, by everyone. That is the wrong place to spend vigilance in a system whose core promise is that the numbers are right. Frontend TypeScript is unaffected because no money arithmetic happens there (§8).

**Reversibility:** this choice is cheap to reverse *before* Phase 1 and expensive after. The domain model, rules and API contract in `15`–`27` are stack-neutral and would survive a different backend choice intact.

---

## 2. Repository structure

Single repository, modular monolith (`21`§1). Module boundaries are compile-time enforced.

```
crm/
├── backend/
│   ├── src/main/java/com/{org}/crm/
│   │   ├── platform/            # cross-cutting, no domain knowledge
│   │   │   ├── money/           # Money type, rounding policy, scale rules
│   │   │   ├── audit/           # AuditEvent writer, @Audited interceptor
│   │   │   ├── errors/          # error catalogue, problem responses
│   │   │   ├── tenancy/         # tenant context, RLS session binding
│   │   │   └── security/        # authn, role + branch scoping
│   │   ├── commercialmodel/     # ★ THE ONLY PLACE MODEL BRANCHING LIVES
│   │   │   ├── CommercialModel.java
│   │   │   ├── ModelPolicySet.java
│   │   │   ├── PolicyResolver.java
│   │   │   ├── own/             # 8 own-inventory policy impls
│   │   │   └── brokered/        # 8 brokered-inventory policy impls
│   │   ├── identity/            # users, roles, branches, invitations
│   │   ├── crm/                 # leads, customers, activities, tasks
│   │   ├── inventory/           # developers, projects, phases, units
│   │   ├── reservations/
│   │   ├── deals/               # deals, discounts, deal lifecycle
│   │   ├── paymentplans/        # templates, instances, schedule generator
│   │   ├── collections/         # payments, allocations, metrics
│   │   ├── commissions/         # rules, entitlements, calculator
│   │   ├── reporting/           # read-only aggregates, dashboards
│   │   ├── documents/
│   │   ├── notifications/
│   │   └── integrations/        # import/export
│   ├── src/main/resources/db/migration/     # Flyway
│   └── src/test/java/...                    # mirrors main + architecture/
├── frontend/
│   └── src/{app,features,components,api,i18n}/
└── docs/                        # documents 01–29 live here
```

**Module rules, enforced by ArchUnit:**
1. No module may access another module's repositories or entities directly — only its published service interface.
2. `platform` depends on nothing in the domain; domain modules depend on `platform`.
3. `reporting` has **read-only** access to financial modules and no write path.
4. `commissions`, `collections`, `paymentplans` form the financial core; only they write financial tables.
5. The string `commercial_model` and the `CommercialModel` enum may not appear outside `commercialmodel/` and the persistence mapping of the two columns that store it. **This is the rule that keeps §3 true.**

---

## 3. Commercial-model containment — the central architectural control

`21`§2a names eight policy points. This section makes them mechanical.

```java
public interface ModelPolicySet {
    InventoryOwnershipPolicy       inventoryOwnership();
    CollectionPolicy               collection();
    CollectionMetricsPolicy        collectionMetrics();
    DownPaymentSatisfactionPolicy  downPaymentSatisfaction();
    ExternalCommissionPolicy       externalCommission();
    InternalCommissionPolicy       internalCommission();
    CancellationPolicy             cancellation();
    DashboardPolicy                dashboard();
}
```

**Resolution.** Exactly one entry point:
```java
ModelPolicySet policies = policyResolver.forDeal(deal);      // reads deal.commercialModel
ModelPolicySet policies = policyResolver.forProject(project);
```
Two implementations of each interface (`own/`, `brokered/`), registered in a single map. There is no third path and no `if` on the enum anywhere else.

**Enforcement (build-fails, not a guideline):**
```java
@ArchTest
static final ArchRule model_branching_is_contained =
    noClasses().that().resideOutsideOfPackage("..commercialmodel..")
        .should().dependOnClassesThat().haveSimpleName("CommercialModel");
```
Plus a rule forbidding `switch`/`if` on the enum outside the package, and a test asserting the policy interface count is exactly 8 — adding a ninth requires deleting that assertion deliberately, which surfaces in review.

**Policies decide; they never compute.** `CollectionMetricsPolicy.appliesTo(deal)` returns a boolean; the overdue calculation itself lives once in `collections` and runs unchanged for every deal it is given. This is what stops the two models from forking the financial engine.

**Absence is explicit.** Where a policy says a capability does not apply, services throw `NotApplicableForCommercialModelException`, surfaced as `422` with `code: NOT_APPLICABLE_FOR_COMMERCIAL_MODEL` (`23`§1 rule 8). Never zeros, never empty lists (R-BRK-1).

---

## 4. Database schema

`22` is the schema specification and is unchanged except for the rounding clarification made at this gate. Implementation notes:

**Tenant isolation, two independent layers.** (a) Postgres RLS on every business table keyed on `app.current_tenant_id`, set per transaction from the authenticated principal; (b) repository-level tenant predicates. Neither is trusted alone.

**Composite foreign keys in the financial path** include `tenant_id`, e.g. `payment_allocations(tenant_id, installment_id) → installments(tenant_id, id)` — a cross-tenant financial reference becomes structurally impossible rather than merely unlikely.

**Constraints that must exist before any financial code is written** (from `22`§7, in dependency order):
- C1 one active deal per unit · C2 one active reservation per unit (partial unique indexes)
- C3 one active payment plan per deal
- C4 non-negative and bounded money checks
- C5 allocations ≤ parent payment (deferred constraint trigger)
- C6 schedule sums to net value, exact equality (trigger at plan activation)
- C7 immutability of settled financial rows
- C10 commercial model ⟺ developer presence
- C11 payments only on own-inventory deals
- C12 inbound commissions only on brokered deals
- C13 commission rule snapshot non-null
- C14 non-overlapping commission rules (exclusion constraint)
- **C15 (new, see §9):** unique `(tenant_id, deal_id, direction, participant_role, counterparty_id)` on `commissions` — the structural guard against double-counting a participant.

**Money columns:** `NUMERIC(18,2)` for amounts, `NUMERIC(18,4)` for rates. Mapped to `BigDecimal` with `scale = 2` asserted on write.

**Dates:** `DATE` for `due_date`, `payment_date`, `deal_date`, `delivery_date`, `down_payment_confirmed_at` — no time component, so a payment at 23:00 Cairo on the 31st belongs to that month regardless of server timezone. `TIMESTAMPTZ` for event timestamps.

---

## 5. Migrations

Flyway, forward-only, `V{n}__{description}.sql`. Rules:
1. Every migration is reviewed as SQL; no auto-generated schema diffs.
2. Migrations touching financial tables carry an explicit approval gate in CI (`24`§9).
3. Constraints ship **in the same migration as the table they protect** — never "add the constraint later," which is how a table accumulates data that violates it.
4. Backfills on financial tables require a dry-run report and a before/after reconciliation query (`22`§10).
5. Baseline migration sequence follows the epic order in §16, so the schema grows with the features rather than arriving whole.

---

## 6. Authentication and authorization

**Authentication:** email + password (BCrypt/Argon2), server-side sessions or short-lived JWT with refresh. Invitation flow, password reset. SSO remains Future.

**Authorization — three independent dimensions, all evaluated server-side:**
1. **Role** — what actions (fixed roles, `16`§3).
2. **Branch scope** — whose records (agent → own, TL → team, BM → branch, Owner → all).
3. **Field-level** — national ID and commission figures restricted beyond record access.

**Separation of duties, enforced as two-principal actions:** voiding a payment (FIN + BM) and approving a cancellation (BM + Owner). Implemented as an approval record referencing two distinct users, not as a role that can do both.

**Authorization is never expressed in the frontend.** The UI hides what a user cannot do; the API refuses it regardless. `404` rather than `403` for cross-tenant records, so existence never leaks.

---

## 7. API and error handling

Contract per `23`. Implementation notes:

**Errors** use a single catalogue with stable codes. Financial errors **always carry the numbers**:
```json
{ "error": { "code": "PLAN_INVARIANT_VIOLATION",
             "message": "Schedule total does not equal net value",
             "details": { "net_value": "2850000.00", "schedule_total": "2849999.84",
                          "difference": "0.16" } } }
```
A validation failure that says only "invalid" is useless to someone reconciling a schedule at month end.

**Status codes:** 400 malformed · 401/403 auth · 404 not found *and* cross-tenant · 409 conflict (unit taken, idempotency replay with different payload) · 422 business-rule violation, including `NOT_APPLICABLE_FOR_COMMERCIAL_MODEL` · 429 rate limited.

**Idempotency:** `Idempotency-Key` required on payment creation and imports; key + request hash stored, replay returns the original response.

**Money in JSON is always a string** (`"2850000.00"`), never a JSON number. Serializer configured globally; a test asserts no `BigDecimal` serializes as a float.

---

## 8. Domain and service layer

**Layering per module:** Controller → ApplicationService (transaction boundary) → Domain → Repository. Controllers contain no business logic; repositories contain no rules.

**Pure calculators.** The financial calculators are pure functions with no repository access — inputs in, values out:

| Service | Input | Output | Purity |
|---|---|---|---|
| `ScheduleGenerator` | plan parameters, net value | list of installments | Pure |
| `AllocationEngine` | installments + payment amount + strategy | allocation instructions | Pure |
| `CollectionMetricsCalculator` | installments + allocations + as-of date | expected/actual/outstanding/overdue/aging | Pure |
| `CommissionCalculator` | rules + deal facts + peer results | entitlement amounts | Pure |
| `ScheduleDateCalculator` | start date, frequency, count | due dates | Pure |

Purity is the testing strategy: every rule in `17` becomes a unit test with no database, no Spring context and no fixtures beyond plain values. Persistence is a separate, thinner concern.

**Money type.** A `Money` wrapper over `BigDecimal` fixing scale 2 and the rounding policy, with arithmetic that refuses to mix currencies. Raw `BigDecimal` is banned in domain signatures by an ArchUnit rule, so the rounding policy cannot be bypassed by accident.

**Transactions.** One business operation, one transaction. `activateDeal` writes the deal, plan, N installments, unit status, unit history and all commission entitlements atomically or not at all. Non-transactional side effects (email) are enqueued inside the transaction and dispatched after commit.

---

## 9. Commission engine — distribution and anti-double-counting

This section answers the gate's explicit requirement: make it impossible to double-count or ambiguously distribute commission.

### 9.1 Distribution semantics — independent claims, not pool slices

**Every entitlement is an independent claim against the tenant.** Outbound entitlements are *not* slices of a pool that must sum to 100%, and the engine never "distributes a remainder." Consequences:
- Three participants at 50%/10%/10% of inbound consume 70%; the tenant retains 30%. Nothing needs to make them sum to anything.
- **Tenant retained margin is derived, never stored:** `retained = inbound.expected − Σ outbound.expected`. Storing it would create a fourth number that can drift from the other three.
- Over-allocation (Σ outbound > inbound) **warns and proceeds** (R-COMM-8) — a knowing loss-leader is legitimate; a silent block is not.

### 9.2 Deterministic computation order

Entitlements may depend on each other (`inbound_commission` basis depends on inbound; `peer_commission` depends on another participant). The engine therefore:

1. Builds the set of applicable rules by resolving most-specific-first per (direction, participant role).
2. Constructs a dependency graph: `inbound → outbound(basis=inbound_commission) → outbound(basis=peer_commission)`.
3. **Topologically sorts and rejects cycles.** A cycle (A derives from B, B from A) is a configuration error caught at rule-save time *and* re-checked at activation.
4. Computes in sorted order, each entitlement seeing only already-computed values.
5. Enforces **maximum dependency depth 3** — beyond that, arrangements become unauditable by the humans who have to explain them.

Because computation happens once at activation and snapshots the result, ordering is deterministic and reproducible from the snapshot alone.

### 9.3 Structural guards against double-counting

| Guard | Mechanism |
|---|---|
| One entitlement per participant per role per deal | **C15** unique index `(tenant_id, deal_id, direction, participant_role, counterparty_id)` |
| At most one inbound per deal | Partial unique index on `(tenant_id, deal_id) where direction='inbound'` |
| No entitlement without a snapshot | **C13** `CHECK (rule_snapshot IS NOT NULL)` |
| Overlapping rules cannot both apply | **C14** exclusion constraint on scope + effective range |
| Re-activation cannot duplicate | Activation is idempotent: entitlements are created once, keyed by the unique index; a retry is a no-op |
| Peer cycles | Topological sort with cycle rejection (§9.2) |
| Settled amounts never feed a basis | Bases read *expected* amounts only; `settled_amount` is never an input to another calculation |

### 9.4 Snapshot immutability

At activation, the resolved rule's full definition, the basis amount, and the rate applied are written into `rule_snapshot` and the amount columns. Thereafter:
- Editing or expiring a rule has **no effect** on existing entitlements.
- Recomputation is never triggered by rule changes — only by an explicit, audited deal-level override.
- The snapshot is sufficient on its own to re-derive the figure years later, which is what makes it defensible in the disputes `02`§7 documents as this industry's most common trust failure.

### 9.5 Worked example — brokered, three participants, one peer-derived

```
Deal: net 2,850,000 · brokered · inbound rule 3% of net_value
Rules: agent 50% of inbound_commission
       team_leader 10% of peer_commission(agent)
       referrer (Partner Co) 10% of inbound_commission

Order: inbound → agent → team_leader ; referrer independent

inbound      3%  × 2,850,000 = 85,500.00
agent       50%  ×    85,500 = 42,750.00
team_leader 10%  ×    42,750 =  4,275.00   ← peer-derived, NOT 8,550
referrer    10%  ×    85,500 =  8,550.00
Σ outbound                    = 55,575.00
retained (derived)            = 29,925.00
```
The team-leader figure is the point: 4,275 (share of the agent) versus 8,550 (share of the company) — both are real arrangements, and a system without `peer_commission` would silently produce the wrong one.

### 9.6 Trigger validity matrix

Not every trigger makes sense for every direction and model. Validated at rule save:

| Trigger | Inbound (BRK) | Outbound (OWN) | Outbound (BRK) |
|---|:---:|:---:|:---:|
| `on_deal_activation` | ✓ | ✓ | ✓ |
| `on_down_payment` | ✓ | ✓ | ✓ |
| `on_deal_completion_and_down_payment` | ✓ (default) | ✗ — would defer agent pay until a multi-year schedule completes | ✓ |
| `on_inbound_received` | ✗ | ✗ — no inbound exists | ✓ |
| `pro_rata_on_collection` | ✗ | ✓ (P2) | ✗ — no collection data |
| `manual` | ✓ | ✓ | ✓ |

**Runtime fallback:** a tenant-scoped rule may be valid for one model and not another (e.g. `inbound_commission` basis on a mixed-portfolio tenant). Where a rule cannot apply to a given deal, the engine **skips the entitlement and records a warning on the activation result** rather than failing the deal or silently producing a wrong number.

---

## 10. State machines

Implemented as explicit transition tables, not scattered `if` statements. Each aggregate has a `TransitionTable` declaring `(from, to, requiredRoles, guards, sideEffects, auditRequired)` mirroring `18` row for row, so the specification and the code can be diffed by eye.

- Illegal transitions throw `IllegalStateTransitionException` → `422` with both states named.
- Side effects execute inside the triggering transaction (`18`§10 rule 1).
- **Derived states are never persisted** — installment `overdue`, inbound-commission `overdue`, aging buckets, and "partially settled" (§11 F-06) are computed on read.

A test asserts every transition in `18` exists in the table and every table entry exists in `18`.

---

## 11. Implementation decisions taken at this gate

Findings from the validation pass in §15, resolved here without changing product behaviour:

| # | Decision |
|---|---|
| F-02 | Base installment floors; everything else half-up. Patched into `17` conventions and `22`§8. |
| F-03 | Zero down payment ⇒ `down_payment_satisfied` true from activation, both models; BRK confirmation action not offered. Patched into `17` as R-DP-3. |
| F-05 | Trigger × direction × model validity matrix (§9.6), validated at rule save. |
| F-06 | Partial settlement is **derived** from `settled_amount` vs `expected_amount`; no `partially_settled` state is stored. Consistent with "derived states are never persisted." |
| F-07 | On brokered plan closure, installments remain `scheduled`. They were never obligations to the tenant, and R-SCOPE-2 already excludes them from every aggregate. Voiding them would imply cancellation, which is a different fact. |
| F-08 | A rule that cannot apply to a deal's model is skipped with a warning on the activation result (§9.6 runtime fallback). |
| F-09 | Reservation deposit and down-payment confirmation are distinct fields with distinct meanings and must never be wired to each other; a brokered reservation deposit does not satisfy R-DP-1. |

---

## 12. Validation rules

Three layers, deliberately redundant in the financial path:

1. **Request validation** — types, ranges, required fields (Bean Validation). Rejects malformed input at the edge.
2. **Domain invariants** — R-PLAN-4, allocation bounds, discount ≤ gross, commission dependency acyclicity. Enforced in domain services; these produce `422` with numeric detail.
3. **Database constraints** — C1–C15. The final backstop; a violation here indicates an application bug and is alerted on, not merely logged.

An invariant enforced only in application code will eventually be bypassed by a new code path. Every financial invariant therefore exists at layers 2 **and** 3.

---

## 13. Audit trail

- Append-only `audit_events`; no update/delete path exists in the application and the DB role lacks those grants.
- Written via an `@Audited` interceptor for standard CRUD **plus explicit domain-event writes** for state transitions, because a transition's meaning is not inferable from a column diff.
- Records: actor, tenant, entity type/id, action, before/after (financial rows), reason, correlation id, timestamp.
- **Every transition marked "Audit: Yes" in `18` has a corresponding test** asserting the record is written — audit coverage is verified, not assumed.
- Financial exports and national-ID access are audited (`19` F9/F3).

---

## 14. Testing strategy

| Layer | Tooling | What it covers |
|---|---|---|
| Unit | JUnit 5 + AssertJ | Every rule in `17`, against pure calculators (§8). No DB, no Spring. |
| **Property-based** | jqwik | Schedule generation invariants (§15 of `29`). **Written before the generator.** |
| Integration | Testcontainers (real Postgres) | Constraints C1–C15, RLS, transactions, concurrency. Constraints cannot be tested against H2. |
| Concurrency | Testcontainers + parallel execution | Double-reservation and double-activation must fail at the DB, not in application logic. |
| Architecture | ArchUnit | §2 module rules and §3 model containment. Build-breaking. |
| Contract | OpenAPI + REST Assured | `23` endpoints, error codes, money-as-string serialization. |
| Reconciliation | Integration | Every dashboard aggregate equals the sum of its drill-down rows, on a seeded dataset. Runs in CI. |
| Security | Integration | Cross-tenant access attempts on every endpoint; role matrix; field-level restrictions. |

**Test data:** the canonical fixture from `17`§5 is a shared constant used across layers, so a rounding regression fails in several places at once rather than hiding in one.

Full case-by-case specification: `29_Financial_and_Commission_Test_Specification.md`.

**How integration tests obtain PostgreSQL.** The requirement above is that the database is real, not that a particular tool started it. `AbstractPostgresIT` tries three sources in order: a server named by `crm.it.db.url`; a `postgres:16-alpine` container via Testcontainers; and an embedded PostgreSQL 16 run as a local process. Each is a real PostgreSQL, so the guarantee holds either way.

The third exists because the second is not always available. Docker 29 rejects the API version the Testcontainers client negotiates, answering `/info` with an empty HTTP 400 that reads as "no Docker present" (the POM pins `api.version` to work around it), and a developer may simply not run Docker at all. Without a fallback, a broken local Docker blocks the whole suite, which invites the habit of running `-DskipITs` permanently — and an integration test nobody runs is not a test. The embedded process makes `mvn verify` self-sufficient.

The first source never runs tests against the database it names: that URL is used only to connect, and each run creates its own database, migrates it from empty and drops it on exit, so no developer's working data is reachable from a test. CI sets nothing and has Docker, so the container path stays the one under continuous verification.

---

## 15. Validation findings from the design review

Full classification in `IMPLEMENTATION_READINESS.md`. Summary: **two genuine contradictions** (F-01 commission-rule-absence behaviour; F-02 rounding wording) — both resolved and patched into `17`, `19`, `22`, `26`. **One genuine gap** (F-04, no basis for peer-derived commission) — resolved by adding `peer_commission` to `26`§4 and §9 here. **Six ambiguities** resolved as implementation decisions (§11). **Zero blockers.**

---

## 16. Seed and demo data strategy

Three seeded tenants, each exercising a different path, so a developer can see all behaviour without configuring anything:

1. **`demo-own`** — own-inventory developer selling direct. One project, 40 units, outbound-only commission (agent + team leader on `net_value`). Full payment history on several deals including partial, over- and late payments.
2. **`demo-brokered`** — brokerage selling a developer's stock. Developer record, inbound 3% rule, outbound agent 50% of inbound + TL 10% of peer(agent) + external referrer partner. Deals at each lifecycle stage including one awaiting down-payment confirmation.
3. **`demo-mixed`** — both project types in one tenant, to exercise the side-by-side dashboard and prove cash and commission are never summed.

**Fixture deals** (in `demo-own` unless noted):
- The canonical `17`§5 deal, exactly.
- A 0%-down plan (R-DP-3 path), in both tenants.
- A cash plan (single installment).
- A 1-installment plan (degenerate rounding case).
- A deal with a voided payment and its reversal.
- A deal with an overpayment producing a credit balance.
- A brokered deal with a peer-derived team-leader commission (§9.5).

Seeds are **idempotent and environment-gated** — never runnable against production, enforced by a profile check plus a refusal if the target database contains non-seed tenants.

---

## 17. Architecture decision summary

Carrying forward A1–A13 from `21`§15, with additions:

| # | Decision | Rationale |
|---|---|---|
| A14 | Java 21 + Spring Boot + PostgreSQL | Native exact decimal; the correctness risk is arithmetic, not throughput |
| A15 | ArchUnit rules are build-breaking, not advisory | §3's containment is worthless if it relies on reviewer vigilance |
| A16 | Financial calculators are pure functions | Makes every rule in `17` unit-testable without infrastructure |
| A17 | `Money` type; raw `BigDecimal` banned in domain signatures | The rounding policy cannot be bypassed by accident |
| A18 | Commission entitlements are independent claims, not pool slices | Removes the whole class of "distribute the remainder" ambiguity |
| A19 | `peer_commission` basis with acyclic dependency ordering | Expresses a real arrangement that would otherwise be silently mis-modelled |
| A20 | C15 uniqueness on (deal, direction, role, counterparty) | Double-counting becomes structurally impossible, not merely unlikely |
| A21 | Property-based schedule tests written before the generator | The highest-value test in the project, most valuable before the code exists |
