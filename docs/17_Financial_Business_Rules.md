# 17 — Financial Business Rules

**Revision 2 — September 2026.** Revised for the multi-commercial-model direction. Changes from revision 1: collections rules are now model-aware; the down-payment-satisfied predicate is introduced; commission formulas are generalised to the configurable engine in `26`; the list of "blocking" confirmations is replaced by a configuration checklist, because configurable values are not blockers.

**Conventions.** All amounts EGP, fixed-point decimal, 2 places. Rounding is **half-up** at every point **except the base installment (R-INST-1), which floors deliberately** so that the rounding remainder is always non-negative and lands on the final installment (R-INST-2). No floating point in the financial path. `due_date` and `payment_date` are calendar dates; "today" is the tenant's local date (Africa/Cairo).

**Model shorthand:** **OWN** = `own_inventory`, **BRK** = `brokered_inventory` (`25`).

---

## 1. Notation

| Symbol | Meaning |
|---|---|
| `P` | Reporting period with bounds `[P.start, P.end]` |
| `D` | As-of date, default today |
| `S` | Scope filter (all / project / customer / agent / plan / branch) |
| `I` | Installment · `A` | Allocation |

**R-SCOPE-1.** Every aggregate excludes deals with `status = cancelled` and installments with `status = void`.
**R-SCOPE-2 (new).** Every **cash-collection** aggregate additionally includes only deals where `commercial_model = own_inventory`. Commission aggregates apply to both models. Mixing brokered deals into a cash-collection figure would report money the tenant never receives.

---

## 2–5. Deal value, discount, plan decomposition, installment generation

**Unchanged from revision 1 and model-agnostic.** The payment-plan engine behaves identically in both commercial models; only what is *done* with the resulting schedule differs.

**R-VAL-1** Gross value = unit list price captured and frozen at deal creation.
**R-VAL-2** Authorised override of gross value, recorded with original and actor.
**R-DISC-1** Discount amount: `value` if fixed; `round(gross × value/100, 2)` if percent.
**R-DISC-2** Multiple discounts sum; percentages each computed against gross, never compounded.
**R-DISC-3** `net_value = gross_value − total_discount`.
**R-DISC-4** `net_value > 0`.
**R-DISC-5** Discounts above a tenant threshold require manager approval before activation.
**R-PLAN-1** Down payment from percent or fixed amount.
**R-PLAN-2** Delivery payment from percent of net.
**R-PLAN-3** `financed = net − down − delivery`.
**R-PLAN-4** **Invariant:** `down + delivery + Σ installments = net_value`, exact to the cent. Asserted at generation; violation blocks activation. Applies in **both** models.
**R-PLAN-5** Cash plans generate a single installment equal to net value.
**R-INST-1** `base = floor_to_2dp(financed / count)`.
**R-INST-2** Remainder added to the **last** installment.
**R-INST-3** Schedule = down payment + N installments + delivery installment.
**R-INST-4** First installment date = deal date + configured offset.
**R-INST-5** Subsequent dates at the plan frequency interval.
**R-INST-6** Month arithmetic clamps to month end and retains original day-of-month (31 Jan → 28 Feb → 31 Mar).
**R-INST-7** No weekend/holiday shifting.
**R-INST-8** Manual schedule override permitted pre-activation; R-PLAN-4 re-asserted.
**R-INST-9** Schedule frozen once any allocation exists.

### Canonical worked example (test fixture — unchanged)
```
Unit list price          3,000,000.00
Discount 5%               -150,000.00
Net value                2,850,000.00
Down payment 10%           285,000.00
Delivery payment 5%        142,500.00
Financed amount          2,422,500.00
8 years quarterly = 32 installments
Base installment            75,703.12
Remainder                        0.16 → added to installment 32
Installments 1–31           75,703.12   ·   Installment 32   75,703.28
Check: 285,000 + 142,500 + (75,703.12×31) + 75,703.28 = 2,850,000.00 ✓
```

---

## 6. Payments and allocation — **OWN only**

**R-PAY-0 (new).** Payments may be recorded **only** against deals where `commercial_model = own_inventory`. An attempt to record a payment on a brokered deal is rejected. Rationale in `16`§15: a partially-populated ledger invites people to read totals that do not mean what they appear to mean.

**R-PAY-1** Only `cleared` payments count as collected.
**R-PAY-2** Auto-allocation applies a payment oldest-due-first, then by sequence; each installment absorbs `min(remaining, expected − allocated)`.
**R-PAY-3** Manual allocation permitted and audited.
**R-PAY-4** Overpayment flows to future installments, then to a deal credit balance; reported, never silently absorbed.
**R-PAY-5** Partial payments leave the unpaid portion outstanding and ageing.
**R-PAY-6** Void/bounce reverses all allocations via reversal rows and recomputes installment status.

---

## 7. Down-payment satisfaction — **the model-aware predicate**

**R-DP-1 (new).** `down_payment_satisfied(deal)` is a single predicate resolved by commercial model:

- **OWN:** true when the installment of `kind = down_payment` has `status = paid` (i.e. `allocated_amount ≥ expected_amount` from cleared payments).
- **BRK:** true when `deal.down_payment_confirmed_at IS NOT NULL` — the manually-recorded confirmation milestone (`25`§4).

**R-DP-2.** In BRK the confirmation records a date and confirming user, and requires no amount reconciliation. It is a fact the tenant learns, not a ledger entry.

**R-DP-3 (added at the implementation gate) — zero down payment.** Where a plan has `down_payment_amount = 0` there is no down payment to satisfy, and the predicate would otherwise be permanently false. In that case `down_payment_satisfied(deal)` is **true from deal activation** in both models, and the down-payment confirmation action is not offered in BRK. This is not a hypothetical: 0%-down plans are documented in the Egyptian market (`02`§11). Without this rule, a 0%-down brokered deal could never accrue inbound commission under the default trigger — the tenant would simply never be paid by the system's reckoning.

**Why this exists.** The confirmed inbound-commission trigger depends on the down payment having been received, while developer-side collection reconciliation is out of MVP. This predicate is what lets both be true at once, and it means no downstream rule has to branch on the commercial model — the commission engine simply asks whether the down payment is satisfied.

---

## 8. Expected, actual, outstanding, overdue

**R-EXP-1 — Expected collections (both models, different meaning).**
`Expected(P,S) = Σ I.expected_amount` where `I.due_date ∈ P`, excluding void installments and cancelled deals.
- **OWN:** money the tenant expects to receive. Reported as a collections figure.
- **BRK:** money the *developer* expects to receive from the customer. Reference information only — **never** presented as tenant cash, and excluded from cash-collection dashboards by R-SCOPE-2. Useful for customer service and commission-timing context.

**R-ACT-1 — Actual collections. OWN only.**
`Actual(P,S) = Σ A.allocated_amount` for allocations whose payment is `cleared` with `payment_date ∈ P`.
Note: actual collections in P are not necessarily collections *of* installments due in P — a March payment against a January installment counts in March's Actual and reduces January's Overdue. Labelling must reflect this.

**R-ACT-2** `CollectionRate(P,S) = Actual / Expected` where Expected > 0. **OWN only.**

**R-OUT-1 — Outstanding (due) as of D. OWN only.**
`Σ (expected − allocated)` over installments with `due_date ≤ D`, positive results only.

**R-OUT-2 — Total contracted remaining as of D. OWN only.**
`Σ (expected − allocated)` over all non-void installments regardless of due date. Must be labelled distinctly from R-OUT-1 — conflating them dramatically overstates the collection problem.

**R-OVD-1** Grace period: an installment is overdue after `due_date + grace_days`. Default 0, tenant-configurable.

**R-OVD-2 — Overdue as of D. OWN only.**
`Σ (expected − allocated)` where `D > due_date + grace_days` and the difference is positive. Only the unpaid portion of a partially-paid installment is overdue.

**R-OVD-3** `DPD(I,D) = D − I.due_date`, computed only for overdue installments.

**R-AGE-1** Aging buckets, default `Current / 1–30 / 31–60 / 61–90 / 90+` days past due; boundaries tenant-configurable. **OWN only.**

**R-AGE-2** A customer appears in every bucket in which they hold overdue installments, with the amount in each.

**R-BRK-1 (new).** For BRK deals, Outstanding, Overdue and Aging are **not computed and not displayed**. The UI states plainly that collection is handled by the developer, rather than showing zeros — a zero reads as "nothing owed," which is false and worse than an honest absence.

**R-PEN-1** Late-payment penalties and interest are not computed in MVP, in either model.

---

## 9. Forecast

**R-FCT-1 — Cash collection forecast. OWN only.**
`Forecast(P,S) = Expected(P,S)` for future periods — the contractual schedule, cancelled deals excluded.

**R-FCT-2** Forecast excludes currently-overdue amounts (they belong to the period they were due in) but the dashboard shows overdue alongside, never merged.

**R-FCT-3 — Risk-adjusted forecast. P2, OWN only.** Weights scheduled amounts by payment-history confidence. Deferred: it needs history the tenant will not have at launch.

**R-FCT-4 — Commission forecast (new). Primarily BRK.**
`CommissionForecast(P) = Σ Commission.expected_amount` for inbound commissions whose trigger is expected to be met in P, or whose `due_date ∈ P` once claimed.
This is the CEO's real forward-cash question in the brokered model — the tenant's incoming money is commission, not customer installments (`25`§4).

---

## 10. Commission — generalised

Full engine specification in `26_Commission_Rules_and_Splits.md`. The rules below are the computational core.

**R-COMM-1 — Amount.**
`expected_amount = f(basis_amount, calc_type, value)`:
- `percent` → `round(basis_amount × value / 100, 2)`
- `fixed` → `value`
- `tiered` (P2) → rate selected from `tiers` by the basis, then as `percent`

**R-COMM-2 — Basis resolution.** `basis_amount` per the rule's `basis`: `gross_value`, `net_value`, `inbound_commission` (the deal's inbound expected amount), `collected_amount` (OWN only, P2), or ignored for `fixed`.
**Ordering constraint:** outbound commissions using `inbound_commission` as basis are computed **after** the inbound commission on the same deal. Deal activation computes inbound first, then outbound.

**R-COMM-3 — Rule resolution.** Most-specific-first: `deal → project → developer → tenant`. Behaviour when nothing resolves differs by direction *(clarified at the implementation gate — revision 2 documents contradicted each other here)*:
- **Inbound, brokered deal:** activation is **blocked**. A brokered deal exists to earn commission; activating one with no inbound rule is a configuration error, not a business choice.
- **Outbound, either model:** activation **proceeds with a warning** and no entitlement is created. A salaried team with no commission scheme is legitimate.

**R-COMM-4 — Snapshotting.** The resolved rule is copied to `rule_snapshot` at deal activation. Later rule edits never alter existing commissions. Mandatory.

**R-COMM-5 — Inbound existence.** Inbound commissions are created **only** for BRK deals. OWN deals produce outbound rows only.

**R-COMM-6 — Inbound trigger (confirmed default).** `on_deal_completion_and_down_payment`: the commission becomes `accrued` when `deal.status = completed` **and** `down_payment_satisfied(deal)` (R-DP-1). Configurable per `26`§5.

**R-COMM-7 — Outbound trigger.** Configurable: `on_deal_activation`, `on_down_payment`, `on_inbound_received`, `pro_rata_on_collection` (OWN, P2), or `manual`. No system default is imposed; the tenant chooses, with the cash-flow trade-off documented in `26`§5.

**R-COMM-8 — Over-allocation warning.** Where inbound exists, warn if `Σ outbound expected > inbound expected`. Never block.

**R-COMM-9 — Clawback.** Cancellation moves affected commissions to `clawed_back` via an offsetting `CommissionAdjustment`. Originals are never edited.

**R-COMM-10 — Pro-rata accrual (P2, OWN).** `accrued = expected × (collected_amount / net_value)`, recomputed on each cleared payment.

---

## 11. Cancellation — model-aware (P2)

**R-CAN-1 — Refund basis. OWN only.** `refund = f(total_cleared_payments, policy)`. The policy is configurable with no system default; `02`§9 documents two incompatible real-world models and no Egyptian standard.

**R-CAN-2 — Side effects, common to both models.** Void remaining installments (preserving allocation history); Unit → `available`; clawback commissions (R-COMM-9); freeze the deal's financial totals.

**R-CAN-3 — BRK-specific.** The developer decides the cancellation and any refund. The tenant **records the outcome** — cancellation date, reason, and refund amount if known — and performs its own commission clawback. The tenant does not compute a refund it has no authority over (`27`§5).

**R-CAN-4 — Reporting treatment.** Cancelled deals are excluded from forecast, outstanding and overdue, but historical actual collections remain in past-period figures. Restating history would change previously-reported months, which is unacceptable in a financial report.

---

## 12. Configuration values (not blockers)

These are tenant setup values. Each has a system default where a safe one exists; none requires code changes, and none blocks development (`15`, revised).

| Rule | Configuration | Default |
|---|---|---|
| R-COMM-2 | Commission basis per rule | `net_value` outbound-OWN; `inbound_commission` outbound-BRK; `net_value` inbound |
| R-COMM-6 | Inbound trigger | `on_deal_completion_and_down_payment` (confirmed) |
| R-COMM-7 | Outbound trigger | `manual` until the tenant chooses |
| R-COMM-9 | Clawback automatic or approved | approval-gated |
| R-CAN-1 | Refund policy (OWN) | none — must be configured before P2 cancellation use |
| R-OVD-1 | Grace days | 0 |
| R-AGE-1 | Aging bucket boundaries | 0-30 / 31-60 / 61-90 / 90+ |
| R-DISC-2 | Additive vs compounding discounts | additive |
| R-DISC-5 | Discount approval threshold | none — set per tenant |
| R-INST-2 | Rounding remainder placement | last installment |
| R-INST-4 | First installment offset | one frequency interval |
| R-INST-7 | Weekend/holiday shifting | none |
| R-PAY-4 | Overpayment treatment | held as credit |
| R-FCT-2 | Overdue carry-in to forecast | excluded, shown alongside |

**What changed from revision 1.** Revision 1 marked four commission rules as "⚠ CONFIRM — blocking." They are not blocking: they are configuration. The product requirement is that the engine supports the range; the specific values are tenant setup. The only genuinely confirmed *business* decision now embedded is R-COMM-6's default trigger.
