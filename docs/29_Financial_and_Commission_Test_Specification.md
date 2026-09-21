# 29 — Financial and Commission Test Specification

**Status:** Implementation-ready. Produced at the September 2026 implementation gate.

**Subject:** The concrete test cases that gate financial correctness. These are specifications, not code. Every case names the rule it protects (`17`) so a failing test points at a documented rule rather than an opinion.

**Convention:** all amounts EGP. `⌊⌋₂` = floor to 2dp. Tests marked **[P]** are property-based (jqwik); the rest are example-based.

---

## 1. The canonical fixture — FIN-001

The single most important test in the system. Derived from `17`§5.

```
Given  unit list price        3,000,000.00
  and  a 5% discount
  and  a plan: 10% down, 5% delivery, 8 years quarterly (32 installments)

Then   gross_value            3,000,000.00     (R-VAL-1)
       total_discount           150,000.00     (R-DISC-1)
       net_value              2,850,000.00     (R-DISC-3)
       down_payment_amount      285,000.00     (R-PLAN-1)
       delivery_payment_amount  142,500.00     (R-PLAN-2)
       financed_amount        2,422,500.00     (R-PLAN-3)
       base installment          75,703.12     (R-INST-1, floor)
       remainder                      0.16     (R-INST-2)
       installments 1–31         75,703.12  each
       installment 32            75,703.28     (base + remainder)

Assert down + delivery + Σ(installments) == net_value   EXACTLY   (R-PLAN-4)
       285,000.00 + 142,500.00 + 2,346,796.72 + 75,703.28 = 2,850,000.00
```

**FIN-001a** — the invariant holds to the cent, asserted with `compareTo == 0`, never with a tolerance.
**FIN-001b** — installment count is exactly 34 rows (1 down payment + 32 installments + 1 delivery).
**FIN-001c** — only the final installment differs from the base amount.
**FIN-001d** — the same fixture on a **brokered** deal produces a byte-identical schedule (`27`§2 — the engine is model-agnostic).

This fixture is a shared constant used by unit, integration and reconciliation tests, so a rounding regression fails in several places at once.

---

## 2. Rounding — FIN-010

| ID | Case | Expectation |
|---|---|---|
| FIN-010 | Base installment uses **floor**, not half-up | `⌊2,422,500 / 32⌋₂ = 75,703.12`, not 75,703.13 (R-INST-1, patched convention) |
| FIN-011 | Remainder is always **non-negative** | Direct consequence of floor; asserted as a property **[P]** |
| FIN-012 | Remainder lands on the **final** installment only | R-INST-2 |
| FIN-013 | Exact division leaves zero remainder | e.g. 1,200,000 / 12 = 100,000.00 exactly; all installments identical |
| FIN-014 | Single-installment plan | financed amount lands whole on installment 1; remainder 0 |
| FIN-015 | Percentages round half-up | 5% of 3,000,000.005 → half-up at 2dp (R-DISC-1) |
| FIN-016 | No intermediate pre-rounding | 5% then 10% computed from unrounded net produces the documented figures |
| FIN-017 | Money never serializes as a float | `"2850000.00"` as a JSON string (`28`§7) |

**FIN-018 [P] — the master schedule property.** For any valid combination of `net_value ∈ [1, 10⁹]`, `down_payment_pct ∈ [0, 100]`, `delivery_pct ∈ [0, 100]` (with `down + delivery ≤ 100`), `installment_count ∈ [1, 400]` and any frequency:

> `down_payment + delivery_payment + Σ installments == net_value`, exactly.

This property is written **before** the generator (`28` A21). It is the single highest-value test in the project because it covers the infinite space of plan configurations that example-based tests cannot.

---

## 3. Schedule dates — FIN-020

| ID | Case | Expectation |
|---|---|---|
| FIN-020 | Monthly from 31 Jan | 31 Jan → 28 Feb → 31 Mar → 30 Apr → 31 May (R-INST-6: clamp, but retain original day) |
| FIN-021 | Monthly from 31 Jan in a leap year | → 29 Feb → 31 Mar |
| FIN-022 | Quarterly intervals | +3 months per step |
| FIN-023 | Semi-annual and annual | +6, +12 months |
| FIN-024 | First installment offset | `first_due = deal_date + offset` (R-INST-4) |
| FIN-025 | Delivery installment date | equals the project delivery date, not a computed interval |
| FIN-026 | No weekend/holiday shifting | a due date falling on a Friday stays on that Friday (R-INST-7) |
| FIN-027 **[P]** | Dates are strictly ascending and never skip a period | for any frequency and count |

---

## 4. Payment allocation — FIN-030

Baseline: FIN-001 schedule, own-inventory.

| ID | Case | Expectation |
|---|---|---|
| FIN-030 | Payment exactly equal to one installment | that installment `paid`; no others touched (R-PAY-2) |
| FIN-031 | Payment covering 2.5 installments | two `paid`, one `partially_paid` with the correct residue |
| FIN-032 | Allocation order | oldest `due_date` first, then `sequence_no` |
| FIN-033 | Manual allocation | targets chosen installments, overriding order; audited (R-PAY-3) |
| FIN-034 | Allocation cannot exceed the payment | `Σ allocations ≤ payment.amount`; violation rejected (C5) |
| FIN-035 | Allocation cannot exceed an installment | `allocated ≤ expected` per installment (C4) |
| FIN-036 | `pending` payment allocates nothing | R-PAY-1; installments unchanged |
| FIN-037 | Clearing a pending payment triggers allocation | statuses update on clear |
| FIN-038 **[P]** | Allocation conserves value | `Σ allocations + unallocated_credit == payment.amount`, always |

### Partial payments — FIN-040
| FIN-040 | Two partials summing to one installment | after the second, installment is `paid` |
| FIN-041 | Partial on an overdue installment | only the **unpaid portion** remains overdue (R-PAY-5, R-OVD-2) |
| FIN-042 | Partial of 0.01 | accepted; installment `partially_paid`, not `paid` |

### Overpayment — FIN-050
| FIN-050 | Payment exceeding all due installments | surplus flows to the next unpaid future installments (R-PAY-4) |
| FIN-051 | Payment exceeding the **entire** schedule | residue held as a deal credit balance, reported not absorbed |
| FIN-052 | Credit balance is visible | appears on the deal and in drill-down, never silently netted away |

---

## 5. Reversal — FIN-060

The symmetry tests. These protect the "reversal, never deletion" principle (`18`§10 rule 4).

| ID | Case | Expectation |
|---|---|---|
| FIN-060 | Void a cleared payment | **all** allocations reverse; every affected installment returns to its exact prior status and `allocated_amount` |
| FIN-061 | Deal totals after void | outstanding, overdue and collected return to their pre-payment values, to the cent |
| FIN-062 | Nothing is deleted | the payment row persists with `status = void`; reversal allocation rows exist alongside originals |
| FIN-063 | Bounced payment | no allocation ever applied; installments untouched; notification raised |
| FIN-064 | Re-present a bounced cheque | on clear, allocation runs normally |
| FIN-065 | Void requires two principals | FIN alone is refused; FIN + BM succeeds (`28`§6) |
| FIN-066 **[P]** | Round-trip symmetry | for any payment P applied then voided, the resulting installment state equals the state before P was applied |

FIN-066 is the strongest guarantee in the collections module and should be treated as a release gate.

---

## 6. Outstanding, overdue and aging — FIN-070

As-of dates are explicit in every case; none depends on "now".

| ID | Case | Expectation |
|---|---|---|
| FIN-070 | Outstanding ≠ Total Remaining | on a deal with future installments the two differ; both are labelled distinctly (R-OUT-1, R-OUT-2) |
| FIN-071 | Installment due today, grace 0 | Outstanding **yes**, Overdue **no** |
| FIN-072 | Installment due yesterday, grace 0 | Overdue yes, DPD 1 |
| FIN-073 | Grace 5, installment 3 days past due | Outstanding yes, Overdue **no** (R-OVD-1) |
| FIN-074 | Grace 5, installment 6 days past due | Overdue yes |
| FIN-075 | Aging buckets are exclusive and exhaustive | every overdue installment in exactly one bucket (R-AGE-1) |
| FIN-076 | Customer spanning two buckets | appears in both with correct per-bucket amounts (R-AGE-2) |
| FIN-077 | Late payment across periods | a March payment against a January installment appears in **March** Actual and clears **January** Overdue (R-ACT-1) |
| FIN-078 | Cancelled deal excluded | leaves outstanding/overdue/forecast but **remains** in historical Actual (R-CAN-4) |
| FIN-079 | Void installments excluded | from every aggregate (R-SCOPE-1) |
| FIN-080 **[P]** | Conservation | `Σ expected = Σ allocated + Σ outstanding` across any non-void schedule, any as-of date |

---

## 7. Commercial-model behaviour — MOD-001

| ID | Case | Expectation |
|---|---|---|
| MOD-001 | Own-inventory project with a developer | **rejected** (C10) |
| MOD-002 | Brokered project without a developer | **rejected** (C10) |
| MOD-003 | Record a payment on a brokered deal | **rejected**, `422 NOT_APPLICABLE_FOR_COMMERCIAL_MODEL` (R-PAY-0, C11) |
| MOD-004 | Collections aggregates on a mixed tenant | include own-inventory deals **only** (R-SCOPE-2) |
| MOD-005 | Brokered deal financial tab | returns an explanatory not-applicable state, **never zeros** (R-BRK-1) |
| MOD-006 | Brokered installments stay `scheduled` | for the deal's whole life (`28`§11 F-07) |
| MOD-007 | Inbound commission on an own-inventory deal | **rejected** (C12, R-COMM-5) |
| MOD-008 | Changing a project's model after a deal exists | **rejected** (`25`§3) |
| MOD-009 | Deal carries the model at creation | later project edits do not change the deal's behaviour |
| MOD-010 | Mixed dashboard | cash and commission figures never summed into one total (`25`§7) |

### Down-payment satisfaction — MOD-020
| MOD-020 | OWN, down-payment installment paid | `down_payment_satisfied` true (R-DP-1) |
| MOD-021 | OWN, down-payment installment partially paid | false |
| MOD-022 | BRK, confirmation recorded | true |
| MOD-023 | BRK, no confirmation | false |
| MOD-024 | **Zero down payment, either model** | true from activation; BRK confirmation action not offered (R-DP-3) |

MOD-024 is the edge case that would otherwise permanently block inbound commission on the 0%-down plans documented in the Egyptian market.

---

## 8. Commission engine — COM-001

### Configurability (the gate's explicit checklist)

| ID | Case | Expectation |
|---|---|---|
| COM-001 | Basis `gross_value` | 3% of 3,000,000 = 90,000.00 |
| COM-002 | Basis `net_value` | 3% of 2,850,000 = 85,500.00 |
| COM-003 | Basis `inbound_commission` | 50% of 85,500 = 42,750.00 |
| COM-004 | Basis `peer_commission(agent)` | 10% of 42,750 = 4,275.00 — **not** 8,550 (`28`§9.5) |
| COM-005 | Basis `fixed` | flat amount regardless of deal value |
| COM-006 | Calc type `percent` / `fixed` | both supported in MVP |
| COM-007 | Calc type `tiered` | P2 — rejected with a clear message in MVP |
| COM-008 | Trigger `on_deal_activation` | accrues immediately |
| COM-009 | Trigger `on_down_payment` | accrues when R-DP-1 is satisfied |
| COM-010 | Trigger `on_deal_completion_and_down_payment` | requires **both**; neither alone accrues (R-COMM-6) |
| COM-011 | Trigger `on_inbound_received` | outbound accrues only after inbound settles |
| COM-012 | Trigger `manual` | no automatic accrual |
| COM-013 | Trigger validity matrix | invalid combinations rejected at rule save (`28`§9.6) |

### Participants
| COM-020 | Agent only | one outbound entitlement |
| COM-021 | Agent + team leader | two independent rows |
| COM-022 | Agent + TL + referrer | three rows |
| COM-023 | External partner payee | `counterparty_type = partner` supported |
| COM-024 | Arbitrary additional participant | `role = other` supported |
| COM-025 | Each participant is a separate row | never fields on a shared row |

### Anti-double-counting (the gate's explicit requirement)
| ID | Case | Expectation |
|---|---|---|
| COM-030 | Duplicate participant on one deal | **rejected** by C15 unique index |
| COM-031 | Two inbound entitlements on one deal | **rejected** by partial unique index |
| COM-032 | Re-running activation | idempotent — no duplicate entitlements created |
| COM-033 | Overlapping rules at the same scope/date | **rejected** at save (C14) |
| COM-034 | Σ outbound > inbound | **warns**, does not block (R-COMM-8) |
| COM-035 | Retained margin | derived as `inbound − Σ outbound`; no stored column exists |
| COM-036 | Peer dependency cycle (A←B, B←A) | **rejected** at save and re-checked at activation |
| COM-037 | Dependency depth > 3 | rejected |
| COM-038 | Computation order | inbound computed before any `inbound_commission` basis; peer before dependent |
| COM-039 | `settled_amount` never feeds a basis | only `expected_amount` is an input |
| COM-040 **[P]** | No entitlement is computed twice | for any rule set, each (deal, direction, role, counterparty) appears exactly once |

### Snapshot immutability
| COM-050 | Rule edited after activation | existing entitlements unchanged |
| COM-051 | Rule expired after activation | existing entitlements unchanged |
| COM-052 | Entitlement without a snapshot | **rejected** by C13 |
| COM-053 | Figure re-derivable from snapshot alone | recomputation from `rule_snapshot` reproduces the stored amount |
| COM-054 | Deal-level override | applies, requires a reason, is snapshotted, and is audited |

### Rule resolution and absence
| COM-060 | Most-specific-first | deal → project → developer → tenant |
| COM-061 | **Brokered deal, no inbound rule** | activation **blocked** (R-COMM-3, patched) |
| COM-062 | **No outbound rule** | activation proceeds with a warning, no entitlement |
| COM-063 | Tenant-scoped rule invalid for a deal's model | entitlement skipped, warning on the activation result (`28`§9.6) |

### Lifecycle and clawback
| COM-070 | State path inbound | expected → accrued → claimed → settled |
| COM-071 | State path outbound | expected → accrued → payable → settled |
| COM-072 | Direction-invalid action | claiming an outbound, or marking an inbound payable → `422` |
| COM-073 | Partial settlement | `settled_amount` < `expected_amount`; state remains `claimed`/`payable`; "partially settled" is derived (`28`§11 F-06) |
| COM-074 | Clawback on cancellation | offsetting `CommissionAdjustment` created; original row unedited |
| COM-075 | Agent visibility | an agent cannot read another agent's entitlements |

---

## 9. Template vs. live schedule — TPL-001

Protects the separation in `16` Part A — the rule that a template edit can never reach a customer's schedule.

| ID | Case | Expectation |
|---|---|---|
| TPL-001 | Edit a template after instantiation | every existing `CustomerPaymentPlan` and its installments are **byte-identical** before and after |
| TPL-002 | Archive a template | live instances continue to function; no schedule changes |
| TPL-003 | Delete-equivalent of a template | blocked while instances reference it (FK RESTRICT) |
| TPL-004 | Instance diverges from template | overriding down payment on the instance does not alter the template |
| TPL-005 | Two deals from one template | independent instances; editing one does not affect the other |
| TPL-006 | Regeneration pre-activation | discards and rebuilds the draft schedule; allowed |
| TPL-007 | Edit after first allocation | **rejected** (R-INST-9) |
| TPL-008 | One active plan per deal | second active plan rejected (C3) |

---

## 10. Cancellation — CAN-001 *(P2 — specified now, built later)*

MVP records a cancellation as a deal status change with no refund computation (`20` Part A). These cases gate the P2 build.

| CAN-001 | Remaining installments → `void`; paid history preserved (R-CAN-2) |
| CAN-002 | Unit returns to `available` |
| CAN-003 | Commissions clawed back on both directions (R-COMM-9) |
| CAN-004 | Cancelled deal leaves forecast/outstanding/overdue but **remains** in historical Actual (R-CAN-4) |
| CAN-005 | OWN: refund computed per configured policy; **no system default** exists (R-CAN-1) |
| CAN-006 | BRK: no refund computed; developer's outcome recorded if known (R-CAN-3) |
| CAN-007 | Approval requires BM + Owner |
| CAN-008 | Cancellation is fully audited |

---

## 11. Concurrency and constraints — CON-001

Must run against real PostgreSQL (Testcontainers); these cannot be verified against an in-memory database.

| CON-001 | Two simultaneous reservations on one unit | exactly one succeeds (C2) |
| CON-002 | Two simultaneous deal activations on one unit | exactly one succeeds (C1) |
| CON-003 | Deal activation is atomic | induced failure mid-activation leaves **no** partial state — no orphaned installments, no unit marked sold |
| CON-004 | Two simultaneous payments on one deal | both recorded; allocations remain consistent; no double-allocation |
| CON-005 | Idempotency replay | same key + same payload returns the original response; same key + different payload → `409` |
| CON-006 | Cross-tenant access | every endpoint returns 404 for another tenant's record |
| CON-007 | RLS active | a query without tenant context returns zero rows rather than all rows |

---

## 12. Reconciliation — REC-001

The tests that protect the CEO's trust in the dashboard.

| REC-001 | Every dashboard headline equals the sum of its drill-down rows, on the seeded dataset |
| REC-002 | Period switching (month/quarter/year) is internally consistent — a year's figure equals the sum of its quarters for Expected and Actual |
| REC-003 | Exported CSV totals match on-screen totals exactly |
| REC-004 | Mixed-portfolio tenant: cash and commission totals are reported separately and never summed |
| REC-005 | Nightly reconciliation job detects an induced inconsistency (a hand-corrupted `allocated_amount`) and alerts rather than auto-correcting (`22`§10) |

---

## 13. Audit coverage — AUD-001

| AUD-001 | Every transition marked "Audit: Yes" in `18` writes an audit record — one test per transition, generated from the transition table |
| AUD-002 | Audit records are immutable — update and delete attempts fail at both application and DB level |
| AUD-003 | Financial exports are audited |
| AUD-004 | National-ID access is audited |
| AUD-005 | Audit captures before/after for financial rows |

---

## 14. Release gates

A build may not ship to the pilot unless:

1. **FIN-001** (canonical fixture) passes exactly, to the cent.
2. **FIN-018** (schedule property) passes across its full generated space.
3. **FIN-066** (reversal symmetry) passes.
4. **FIN-080** (conservation) passes.
5. **COM-040** (no double-computation) passes.
6. **CON-001 / CON-002** (no double-selling) pass against real Postgres.
7. **REC-001** (dashboard reconciliation) passes.
8. ArchUnit model-containment rules pass (`28`§3).
9. Audit coverage (AUD-001) is complete for every audited transition.

These nine are not a target; they are the definition of a shippable financial system. A build failing any of them is not "mostly working" — it is producing numbers nobody should act on.
