> **SUPERSEDED — September 2026 product-direction revision.**
> This document recommends interpretation "C: brokerage CRM + collections layer" on the
> assumption that the tenant always sells developer-owned inventory. That assumption is
> no longer correct: the product supports both owned and brokered inventory as a
> configurable commercial model per project. See `25_Commercial_Models.md` for the
> current model and `27_Brokerage_vs_Developer_Workflows.md` for what changes between them.
> The scope discipline in section 4 (this is not a developer ERP) still stands.

# 05 — Recommended Business Model

**Subject:** What kind of system this actually is, and the recommended conceptual operating model for this specific brokerage. This resolves the mission's Section 3 ("traditional brokerage CRM vs. RE sales/deal management vs. brokerage CRM + installment/collection system vs. lightweight RE ERP vs. combination") and Section 11 ("separate brokerage vs. developer functionality").

---

## 1. The five interpretations, and their consequences

| # | Interpretation | What it would mean to build | Consequence if wrong |
|---|---|---|---|
| A | Traditional brokerage CRM | Leads, contacts, activities, pipeline — BRD v0.1 as written, money stays in spreadsheets forever | Fails the explicit new requirement entirely; the brokerage said this is "VERY IMPORTANT" |
| B | Real-estate sales/deal management system | Adds Project/Unit/Deal objects but treats money as a single "deal value" field, no schedule/collections depth | Cannot answer any of the CEO's collection questions (expected/collected/outstanding/overdue) — the actual ask |
| C | **Brokerage CRM + installment/collection management system** | BRD's existing CRM core, plus the money model in `04` (payment plans, installments, collections, commission) as a *visibility and tracking* layer, without taking on developer-style financial custody | Matches the evidence in `02` (developer collects directly) and the explicit new-requirement wording |
| D | Lightweight real-estate ERP | Full GL/AP/bank-reconciliation/tax engine, developer-grade cost accounting | Massive overbuild for a brokerage that (per available evidence) does not itself hold inventory or collect most customer money; converts an 8-week MVP into a multi-quarter accounting-system project |
| E | Combination | Cherry-pick elements of B/C/D based on actual brokerage practice | Only knowable once `10_Business_Questions.md` is answered |

### Recommendation: **C, with configurability toward E**

The evidence supports **C** as the default design target: a brokerage CRM (BRD's existing strength) with a purpose-built payment-plan/installment/collection/commission layer (`04`) bolted on for **tracking and visibility**, not full financial processing. This is not a guess — it follows directly from three independent findings:

1. **`02`§11 (documented):** on primary/off-plan sales in Egypt, the developer collects directly from the buyer; the brokerage's commission is paid separately and does not flow through customer payments. A brokerage that does not hold customer money has no structural need for a general ledger or bank reconciliation engine (interpretation D) — it needs to **know and report on** money it doesn't itself hold.
2. **`03` (competitive):** every product in the market that goes deep on payment/collections (Sell.Do, Covercy Prime, Qobrix, Azdan/NetSuite) does so as a **CRM feature module**, not by becoming a general-ledger accounting system. Even Azdan's NetSuite-based offering — the most "ERP-like" product reviewed — is explicitly positioned as *property sales* software, not general accounting, despite running on an ERP platform underneath.
3. **BRD v0.1 itself (§10 Technical direction):** already commits to a lean, modular-monolith architecture with explicit "early non-goals" (no full warehouse, no plugin marketplace). A full ERP financial engine would contradict this stated technical philosophy for no evidenced benefit.

**However**, `10_Business_Questions.md` Q1–Q3 could change this. If the brokerage confirms it **does** directly collect meaningful money (e.g., reservation deposits always processed by the brokerage, or a growing resale book with no developer in the loop, or an "agency collects and remits" model with some developers), the design in `04` already accommodates this via the per-deal `collection_owner` flag — meaning the recommended architecture degrades gracefully toward **E** without a rebuild. This is why `04` was explicitly designed to support both modes rather than assuming one.

---

## 2. Brokerage functionality vs. developer functionality — the critical boundary

The mission brief is explicit that this distinction must be *investigated*, not assumed. Based on `02` and `03`:

### What belongs to the brokerage (build this)
- Its own **inventory catalog** of what it's allowed to sell across multiple developers' projects (a *read/reference* copy of developer inventory, not the developer's authoritative source of truth).
- **Customer/deal/lead management** for its own sales process (BRD's existing core).
- **Visibility into customer payment status** — a shadow ledger for reporting and commission-triggering purposes (§1 above).
- **Its own commission receivable from each developer** (Leg 1 in `04`§6) and **its own commission payable to its agents** (Leg 2) — both are the brokerage's own money, unambiguously in scope.
- **Its own reporting/forecasting** across all developers/projects it represents — this cross-developer rollup is something no individual developer's system would ever provide, and is a genuine reason the brokerage needs its own system rather than just using each developer's portal.

### What belongs to the developer (do not build)
- Construction-cost accounting, project P&L, land acquisition financing.
- The authoritative unit price list / availability master (the brokerage's copy is a **synced reference**, not the source of truth — stale-data risk noted in `02`§5 is a direct consequence of *not* having a reliable sync/update process, which is itself a requirement, not a reason to duplicate the developer's master data ownership).
- Actual custody of customer payments, escrow, or construction-milestone verification (per `02`§6's escrow-account recommendation — that's a developer/regulatory-side fix, not something this CRM should attempt to solve).
- The developer's own internal commission accounting to its own sales team (irrelevant to the brokerage's system).

### The consequence for the data model
`04`§3.1 makes `Developer` a first-class entity precisely because of this boundary: it is the anchor for "whose inventory is this," "whose commercial terms apply," and "who is the counterparty for Leg 1 commission" — without which the brokerage-vs-developer distinction has no place to live in the schema.

---

## 3. Recommended conceptual operating model (for this brokerage specifically)

Combining the BRD's existing lead-to-reservation flow with the new money model from `04`:

```
Lead → Qualification → Project/Unit selection → Offer
   → Reservation (hold, existing BRD object)
   → Deal/Sale (new: confirmed contract + agreed commercial terms)
   → Payment Plan instance selected/customized (new)
   → Installment Schedule auto-generated (new)
   → Customer pays developer directly (per §1) OR brokerage collects (configurable)
   → Payment/Receipt confirmed in CRM (manual entry or reconciliation import)
   → Outstanding / Overdue derived continuously (new)
   → DeveloperCommission tranche becomes eligible on defined trigger (new)
   → AgentCommissionPayout becomes eligible on defined trigger (new, policy-dependent)
   → Management/CEO reporting rolls up across all of the above (new)
```

This is presented as the **recommended** flow, not an assumed one — it was validated against the BRD's own Section 6 workflow table (which stops at "Reservation workflow") and against every competitive product in `03`, all of which insert some form of "confirmed deal" step between reservation/booking and payment-schedule generation. It should still be walked through with the brokerage before being finalized (see `10_Business_Questions.md`).

---

## 4. What this means for scope discipline

The biggest risk identified across this entire discovery is **scope creep toward interpretation D** — because once a system tracks money at all, "just add invoicing," "just add the general ledger," "just add bank reconciliation" all sound like small next steps. They are not. `06_Proposed_MVP.md` draws the line explicitly; this section is the business rationale for why that line exists: **this brokerage's job is to sell and to know where the money stands, not to become the developer's accounting department.**
