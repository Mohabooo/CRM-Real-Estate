> **SUPERSEDED IN PART — September 2026 product-direction revision.**
> This document assumes a brokerage selling developer-owned inventory, and its two-leg
> commission model (Developer -> Brokerage -> Agent) is valid only for that case.
> The product now supports both owned and brokered inventory as a configurable
> commercial model. For the current design see `25_Commercial_Models.md`,
> `26_Commission_Rules_and_Splits.md` and revision 2 of `16`, `17` and `18`.
> The payment-plan engine, the three-layer money model and the collections
> definitions in this document remain valid and are carried forward unchanged.

# 04 — Payment, Collection & Commission Domain Model

**Subject:** The detailed conceptual design of the "money model" — payment plans, installment generation, collections, overdue tracking, and commission — that BRD v0.1 does not cover (see `01`). This is the core technical deliverable of the whole discovery exercise. Design choices are grounded in `02_Brokerage_Domain_Research.md`, `03_Competitive_Analysis.md`, and general AR/loan-servicing patterns; every non-obvious choice is labeled **[DESIGN DECISION]** with its rationale, or **[OPEN QUESTION]** where it depends on an answer from `10_Business_Questions.md`.

---

## 1. First principle: three-layer separation (expected / actual / derived)

Every credible system reviewed in `03` — regardless of industry (real estate, loan servicing, SaaS billing) — converges on the same architecture. This is the foundation of everything below:

1. **Expected layer** — the **Installment Schedule**: what is due, and when, independent of what has actually been paid. Generated once from a **Payment Plan**, and re-versioned (never silently edited) when terms change.
2. **Actual layer** — the **Payment/Receipt ledger**: what has actually been received, when, by what method, allocated against specific installments.
3. **Derived layer** — **Outstanding**, **Overdue**, and **Collection Forecast**: never stored directly; always computed from layers 1 and 2. This is what §"Collection Forecasting" in the mission brief means by insisting these four concepts "must NOT be treated as the same metric."

**[DESIGN DECISION]** Outstanding and Overdue must be computed fields (or materialized/cached for performance, but always re-derivable), never hand-edited. This is the only way to keep "how much do we expect / have we collected / is outstanding / is overdue" mutually consistent, and matches how every serious system in `03` (Odoo, Sell.Do, Qobrix, loan servicing) is built.

---

## 2. Where the money actually sits — the brokerage-vs-developer question

This is the single most consequential design fork, and it is **not yet answered** (see `10_Business_Questions.md`, Q1–Q3).

Per `02` (§11), Egyptian/MENA evidence indicates: **on primary/off-plan sales, the developer collects directly from the buyer; the brokerage's commission is paid separately by the developer and does not flow through customer payments.** If this holds for this brokerage:

- The CRM's Installment/Payment layer is a **shadow ledger** — it mirrors the developer's official schedule and (ideally) the developer's reported collection status, for the brokerage's own visibility, customer relationship management, and commission calculation. It is **not** a billing/payment-processing system and does not need to (and should not attempt to) take custody of customer money, issue legal receipts, or reconcile against a bank feed.
- "Collected" in this mode means **"confirmed collected by the developer,"** entered into the CRM by an ops user (manually, or via whatever reconciliation the brokerage can get from the developer — a statement, a portal export, a phone call). This is inherently a **trust/confirmation workflow**, not a payment-processing workflow.

If instead the brokerage **does** collect some payments directly (e.g., the reservation deposit, or resale deals where there is no developer in the loop, or an agency-collects-then-remits model), then:

- The relevant subset of Installments needs a **true collection workflow** (who received it, deposit slip/cheque reference, bank, confirmation), and the brokerage may need its own basic receipt-issuance.

**[DESIGN DECISION]** Design the data model to support **both modes per deal** (a `collection_owner` flag on the Deal or Payment Plan: `developer_collects` vs. `brokerage_collects`), rather than assuming one globally. This is cheap to model now and expensive to retrofit later, and the evidence in `02` suggests the answer may even vary by developer/project within the same brokerage.

This directly determines the answer to the mission's Question 3 (Section 3 of the brief): **the system is most consistent with interpretation C — "brokerage CRM + installment/collection *management* system" — where "management" for the developer-collects case means tracking/reconciliation/visibility, not payment processing.** It is explicitly **not** interpretation D (lightweight real-estate ERP): no general ledger, no accounts payable, no bank reconciliation engine, no multi-entity consolidated accounting is implied by anything in the new requirement or the research. See `05_Recommended_Business_Model.md` for the full interpretation analysis.

---

## 3. Entity walkthrough

Full attribute-level detail and relationships are in `08_Data_Model.md`. This section explains the *purpose* of each entity in the money chain.

### 3.1 Developer
A required, first-class entity (BRD currently lists it as "optional," §5.7). Carries the brokerage's commercial relationship with that developer: standard commission rate/range, typical commission-payment tranche policy, and contact/reconciliation details. **Why it matters:** commission rules and payment-plan templates are usually developer- or project-specific (per `02`, rates ranged 2–5% and payment structures like "60/40" are named per-developer conventions), so `Developer` is the natural parent for both.

### 3.2 Project → Unit
Already exists in the BRD (§5.7–§5.8). Extended with: `developer_id`, and a link to one or more available **Payment Plan Templates**.

### 3.3 Payment Plan Template
The reusable, project/unit-level definition of a payment scheme a customer can choose. **This is what BRD §5.8's undefined "PricePlan / payment matrix" should actually become.**

Attributes (conceptual): plan type (`cash` / `down_payment_plus_installments` / `milestone_linked` / `hybrid` / `custom`), down payment (% or fixed amount), installment count, frequency (`monthly`/`quarterly`/`semi_annual`/`annual`/`custom`), duration, delivery-linked payment (% due at/near handover — confirmed structural pattern per `02`§11), escalation/step-up rules (optional), maintenance/club fee handling (separate fee object, with pre- or post-handover timing — negotiable per `02`), currency (EGP; multi-currency **P2**, per §15 non-functional requirements), and a market-shorthand label (e.g. "60/40", "10% + 8 yrs") since this vocabulary is standard in the region (`03`§Tier 2).

**[DESIGN DECISION]** A Unit or Project can offer **multiple** Payment Plan Templates simultaneously (explicit new-requirement ask: "potentially support multiple payment plans for the same project/unit"). Templates are reusable across units within a project; a specific Deal always resolves to one **instance** (§3.6) which may deviate from the template (discounts, custom terms).

### 3.4 Deal / Sale
**New entity, does not exist in BRD.** Represents the confirmed commercial transaction: Customer + Unit + chosen Payment Plan + agreed price + agreed discounts, distinct from the BRD's existing `Reservation` (a time-bound hold). **[DESIGN DECISION]** Keep Reservation and Deal as two separate objects, chained: `Lead → Reservation (hold) → Deal (contract) → Payment Plan instance → Installments`. Rationale: reservations expire or get released constantly (normal, low-stakes); a Deal is a contractual commitment with legal/financial consequences (cancellation, refund, commission) and must not share a lifecycle with something that routinely gets abandoned. Conflating them (as BRD's current Reservation-only model does) would force cancellation/refund logic onto an object designed for informal holds.

### 3.5 Customer
Evolution of BRD's `Lead`/`Contact` once a Deal exists. **[OPEN QUESTION]** whether a Deal can have multiple customers (co-buyers) — posed in `10_Business_Questions.md`.

### 3.6 Customer Payment Plan (Payment Plan instance)
The customer-specific instantiation of a template for one Deal: actual unit price, actual down payment, any negotiated discount, and the resulting parameters that drive schedule generation. Kept distinct from the Template so that editing a Template later never silently changes an existing customer's already-generated schedule — schedules are generated once from the instance and then live independently (see §4).

### 3.7 Installment
One generated line: Deal, due date, expected amount, status (`pending` / `partially_paid` / `paid` / `overdue` — status itself can be derived, see §5), and a link back to which Payment Plan **version** produced it (see §7, plan changes).

### 3.8 Payment / Receipt
One actual money event: amount, date, method (bank transfer, cash, cheque, **PDC** — see §3.9), collected-by (brokerage or developer, per §2), reference/proof (uploaded via BRD's existing Document module), and an allocation to one or more Installments (oldest-due-first by default — standard AR practice per `02`§12 — with manual override).

### 3.9 Post-Dated Cheque (PDC) sub-ledger
**[DESIGN DECISION, P2 unless confirmed MVP-relevant — see `10_Business_Questions.md` Q19]** A PDC is not just a payment method flag; it has its own lifecycle: `issued → deposited → cleared` or `issued → deposited → bounced → replaced/legal`. Modeling it as a sub-object of Payment (cheque number, bank, maturity date, deposit date, status) rather than a single "paid" boolean is what Azdan/NetSuite's product (the only one in `03` to document PDC handling explicitly) does, and matches the UAE-documented practice in `02`. **Do not build this for MVP without confirming Egyptian PDC usage with the brokerage first** — the research found this well-documented for UAE but only inferred for Egypt.

### 3.10 Discount / Adjustment
A modification to a Deal's price or plan (fixed amount or %), timestamped and attributed to a user, feeding into schedule regeneration (§7).

### 3.11 Cancellation / Refund
Captures a cancellation event on a Deal: reason, grace-period tracking, computed refund amount (policy-driven — see `02`§9 for the Maceda Law precedent as one documented model, and the Egyptian informal-forfeiture pattern as another), unit-release trigger, and commission-clawback trigger (§6). **[OPEN QUESTION]** what refund policy applies — this is a business/legal decision, not a technical one (`10_Business_Questions.md`).

### 3.12 Unit Transfer
A Deal-to-new-Unit swap that optionally carries forward the cumulative-payments-made balance as a credit (UAE-documented pattern, `02`§9) rather than always restarting from zero. **[DESIGN DECISION]** Model as Future/P2 — no source found this to be common in Egypt specifically, and it is legally/operationally complex; do not build until confirmed needed.

### 3.13 Commission (two legs — see §6)
`DeveloperCommission` (brokerage's receivable from the developer) and `AgentCommissionPayout` (brokerage's payable to the agent/team leader), each with its own state machine.

---

## 4. The payment-plan engine — what it must calculate

Given a Payment Plan instance (§3.6), the engine generates the Installment schedule (§3.7). Minimum required capabilities, tagged by phase:

| Capability | MVP | P2 | Future | Rationale |
|---|:---:|:---:|:---:|---|
| Cash (single payment) | ✓ | | | Baseline case, must exist |
| Down payment % or fixed + equal installments | ✓ | | | The core, most-requested case |
| Configurable frequency (monthly/quarterly/semi-annual/annual) | ✓ | | | Confirmed standard in `02`§11 |
| Configurable duration (years) | ✓ | | | Egypt observed range 4–16 years |
| First-payment-date offset / grace period before first installment | ✓ | | | Standard in any amortization engine |
| Delivery-linked final payment (e.g., "X% on handover") | ✓ | | | Confirmed structural pattern (`02`§11) |
| Milestone-linked installments (tied to construction stage, not calendar date) | | ✓ | | Documented (Covercy Prime, Azdan) but calendar-based covers most Egyptian NAC examples found; milestone dates require a construction-progress feed the brokerage likely doesn't own |
| Market-shorthand templates ("60/40", "10%+8yrs") as one-click presets | ✓ | | | Confirmed regional vocabulary (`03`) — big adoption/speed win, low build cost (just a labeled preset over the MVP fields above) |
| Discounts (fixed or %) applied before schedule generation | ✓ | | | Common negotiation reality (`02`§9) |
| Rounding rules (which installment absorbs remainder) | ✓ | | | Any amortization engine needs a documented rounding policy — must be explicit, not incidental |
| Custom/manual schedule override (ops can hand-edit generated lines) | ✓ | | | Escape hatch for negotiated deals that don't fit a template — reduces MVP risk of the engine being "too rigid" |
| Balloon payments | | ✓ | | Named in the mission brief; no strong evidence it's common in Egypt specifically — build once requested |
| Escalating/step-up installments | | ✓ | | Not evidenced as common in Egypt; low priority |
| Administrative fees as separate schedule lines | | ✓ | | Maintenance/club fees documented as commonly *separate* from the unit price schedule (`02`§11) — keep them as a distinct fee schedule, not mixed into the unit installments |
| Plan versioning / mid-deal plan changes | | ✓ | | See §7 |
| Multi-currency | | | ✓ | Not evidenced as an MVP need; EGP-only is fine initially per BRD's own market focus |
| Partial payments / overpayments | ✓ (recorded) | ✓ (smart allocation) | | Must at minimum *record* a partial payment against an installment in MVP; automatic allocation intelligence (e.g., spreading an overpayment across future installments) is a P2 refinement |

**[DESIGN DECISION]** Do not build milestone-linked (construction-progress-triggered) schedules for MVP. Every Egyptian example found in `02`§11 was describable as a calendar-based schedule with a delivery-linked final tranche — which the MVP capability list above already covers. True milestone-linking requires the brokerage to have a trustworthy, timely feed of each project's construction status, which is a developer-side capability the brokerage likely does not control. Revisit only if the brokerage confirms it needs to model developer milestone payments precisely (`10_Business_Questions.md`).

---

## 5. Collection tracking — the four metrics, formally

Per the mission brief's explicit instruction, these four must be computed independently, never conflated:

- **Expected collection** (for a period P) = Σ (Installment.expected_amount) for all Installments with due_date in P, across whatever scope is selected (all deals / one project / one customer / one agent / one plan).
- **Actual collection** (for period P) = Σ (Payment.amount) for all Payments with payment_date in P, allocated against installments in that scope. **Note:** actual collection in period P is not necessarily collection *of* installments due in period P — a late payment collected in March against a January installment counts as March's actual collection but reduces January's (and cumulative) overdue.
- **Outstanding balance** (as of date D, scope S) = Σ over all Installments in S with due_date ≤ D of (expected_amount − allocated Payments to that installment). This is **not** the same as "future installments not yet due" — per the Stripe-sourced definition in `02`§6, "outstanding doesn't mean overdue," so a full accounting also needs a **Total Contracted Remaining** figure (all future installments regardless of due date) separate from Outstanding-as-of-today.
- **Overdue amount** (as of date D, scope S) = the subset of Outstanding where due_date < D. Bucketed using the standard aging convention confirmed in `02`§12: **Current, 1–30, 31–60, 61–90, 90+ days past due.**

**[DESIGN DECISION]** Adopt the standard 0-30/31-60/61-90/90+ aging buckets as the default, but make bucket boundaries tenant-configurable (per the Abbacus/loan-servicing finding in the CEO-dashboard research that "should be configurable" is explicit guidance, since conventions vary by institution).

### Collection forecast (forward-looking)
Per the mission's explicit ask ("expected to be collected next month / this quarter / this year"): the forecast for a future period is, at minimum, the sum of scheduled Installments due in that period (a **mechanical forecast**). **[DESIGN DECISION — phase this]:**
- **MVP:** mechanical forecast only (sum of what's contractually scheduled).
- **P2:** a **risk-adjusted forecast** that down-weights installments from customers/deals with a poor payment history (echoing the "promise-to-pay rate" and "roll-rate" concepts documented in the CEO-dashboard research) — this is materially more useful to a CEO than a naive sum, but requires enough historical data to calibrate, so it should not be an MVP dependency.

---

## 6. Commission model — two distinct legs

`02`§7 and §11 make clear this cannot be a single "commission %" field. Two separate relationships exist, and Egyptian evidence shows both are **tranche-based**, not lump sum.

### Leg 1 — Developer → Brokerage ("DeveloperCommission")
- Rate: typically 2–5% of unit price on primary/off-plan sales (developer pays in full; buyer pays nothing extra) — `02`§11.
- **Payment timing is tranche-based**: ~50–70% paid 30–60 days after the customer's down payment; the remaining ~30–50% distributed in installments aligned with the customer's ongoing payments, spread over 6–18 months. Full upfront payment is rare.
- **[DESIGN DECISION]** Model DeveloperCommission with its own tranche schedule, generated similarly to (and ideally linked to) the customer's Installment schedule — e.g., a tranche becomes "earned/accrued" when a triggering customer installment is confirmed collected. This directly operationalizes the Egyptian evidence and is, per `03`§Tier-3 synthesis, **not documented as a built-in pattern in any competitor reviewed** — a genuine differentiator.
- State machine: `expected` (deal signed) → `accrued` (developer-side trigger reached — e.g., customer down payment confirmed) → `invoiced/claimed` (brokerage has billed the developer, if applicable) → `received` (cash in brokerage's account) → `overdue` (developer late per contracted terms) — mirroring the collections state machine in §5, since this is structurally the same problem one level up.
- Commission may additionally be delayed at the developer's discretion depending on their own cash flow (`02`§7/§11) — the brokerage needs its own "Developer AR" visibility for exactly this reason (see `09_CEO_Dashboard_Requirements.md`).

### Leg 2 — Brokerage → Agent/Team Leader ("AgentCommissionPayout")
- Internal policy: split rules by agent, team leader override, possibly branch-level rules. **Not evidenced externally** (this is internal HR/comp policy, not a market fact) — **[OPEN QUESTION]**, posed in `10_Business_Questions.md`.
- **[DESIGN DECISION]** The critical design fork here is the **payout trigger**: does the agent get paid when the deal closes, or only pro-rata as the brokerage itself receives DeveloperCommission tranches? Zoho's commission engine (the best-documented one reviewed, `03`) triggers on delivery+closed-won — i.e., deal-based, not collection-based. Everstage's generic SaaS-commission pattern (CEO-dashboard research) implies an expected→earned→paid lifecycle but doesn't mandate a collection-based trigger either. Given the brokerage itself is only paid in tranches over 6–18 months (Leg 1), paying agents 100% upfront on deal closure exposes the brokerage to real cash-flow risk if a deal later cancels — this is a genuine **business policy decision**, not something to assume; posed as Q13–Q14 in `10_Business_Questions.md`.
- Cancellation must trigger a **clawback** state on any commission (either leg) already accrued/paid against a cancelled deal — confirmed necessary by the Egyptian cancellation/refund evidence in `02`§9 and structurally required by Everstage's documented "adjustments/clawbacks" pattern.

### Commission dashboard states (both legs), per `09`
`Expected → Accrued/Earned → Payable → Paid`, with a parallel `Overdue` (leg 1: developer late) and `Clawback` (either leg, triggered by cancellation) state. This four/five-state model is **[YOUR INFERENCE]** — synthesized from Everstage's generic pattern plus the Egyptian tranche evidence — not copied from any single documented product, because no product researched documents a collections-gated commission model explicitly.

---

## 7. Handling change: discounts, plan changes, cancellations, transfers

**[DESIGN DECISION — core principle]** Never mutate a generated Installment schedule in place once any Payment has been allocated against it. Instead:
- A plan change or discount produces a **new Payment Plan version**, linked to the same Deal, with a defined transition point (installments already paid stay as history; remaining unpaid installments are voided/replaced by the new version's remaining schedule).
- This mirrors the amortization/loan-servicing convention in `03`§Tier-3 ("restructuring produces a new schedule version") and avoids the single worst failure mode possible in a financial system: silently rewriting history that a commission or refund calculation already depended on.

**Cancellation** (§3.11): triggers, in order — (1) optional grace/cure period (policy-dependent, `10_Business_Questions.md`), (2) computed refund amount from cumulative payments made (policy-dependent — Maceda-style tenure-based tiering is one documented model, flat/partial forfeiture is the documented informal Egyptian pattern per `02`§9, so this **must be configurable, not hardcoded**), (3) Unit status reverts to Available, (4) commission clawback on both legs.

**Unit transfer** (§3.12): Future/P2. If built, must support carrying forward a cumulative-payments credit to the new Deal (UAE-documented pattern) as a configurable policy, not a fixed rule, since `02`§9 shows real developers differ on this.

---

## 8. Explicitly out of scope for this CRM (do not build)

To keep this a **brokerage CRM + collections/commission visibility layer**, not a **developer ERP** (see `05_Recommended_Business_Model.md` for the full A–E interpretation analysis):

- General ledger / double-entry bookkeeping / chart of accounts.
- Accounts payable to vendors/contractors, payroll, construction-cost tracking.
- Bank reconciliation / payment-gateway processing (unless the brokerage confirms it directly collects money — §2).
- Multi-entity consolidated financial statements.
- Tax computation/filing (VAT is a non-functional data-capture concern per `07`, not a computed-return feature).

These are hallmarks of interpretation D ("lightweight real-estate ERP") which `05` recommends against unless a specific business question answer (`10`) forces it.

---

## Summary of phase tags (cross-reference to `06_Proposed_MVP.md` and `07_Functional_Requirements.md`)

**MVP:** Payment Plan Template (cash + down-payment+installments + market-shorthand presets), Payment Plan instance per Deal, automatic Installment generation (calendar-based, incl. delivery-linked final payment), manual Payment/Receipt recording with allocation, computed Outstanding/Overdue with standard aging buckets, mechanical Collection Forecast, basic two-leg Commission tracking (manual state transitions acceptable), Deal object distinct from Reservation, Discount on a Deal.

**P2:** Plan versioning/mid-deal changes, milestone-linked schedules, PDC sub-ledger (pending confirmation), risk-adjusted forecast, automated commission-tranche triggers tied to collection events, cancellation refund-computation engine, balloon/escalating installments, administrative fee schedules.

**Future:** Unit transfer with credit-forward, multi-currency, full construction-milestone data feed integration.
