> **SUPERSEDED — September 2026 product-direction revision.**
> Replaced by revision 2 of `20_MVP_Scope_and_Backlog.md`, which covers both commercial
> models and removes the commission blocker recorded here.

# 06 — Proposed MVP

**Subject:** A new MVP recommendation, built from BRD v0.1 + the new business requirement + market research + brokerage pain points — not a copy of BRD v0.1's existing §11 MVP. This keeps BRD's "Recommended MVP" scaffolding (it is sound, see `01`§5) and adds the money-model MVP from `04`, prioritized so the product stays "commercially sellable" without becoming a developer ERP (`05`).

---

## Guiding constraint

The BRD's own MVP philosophy — "operational simplicity... over CRM breadth" (Document control, §1) — should extend to the money model: **ship the smallest set of payment/collection/commission capabilities that lets a CEO answer the questions in the new requirement's "Management/CEO Visibility" section**, and nothing that turns this into accounting software (`05`§4).

---

## Must Have (MVP)

*Carried forward from BRD v0.1 (validated as sound, no change needed — see `01`§5):*
- Multi-tenant + auth + membership (§5.1–§5.2)
- Leads + stages + ownership + manual assignment (§5.3–§5.4)
- Activities + next action + stale views (§5.5)
- Projects + Units, manual entry + CSV import (§5.7–§5.8)
- Reservation v1 (hold → deposit → docs → confirmed/cancelled/expired) (§5.9)
- Basic manager/owner dashboards + CSV export (§9)
- Minimal import/export tooling (§5.19)
- Audit on assignments/reservations/sensitive downloads (§5.20)

*New — the money model (from `04`, tagged MVP there):*
- **Developer** as a first-class entity (commission rate, basic terms) — `04`§3.1
- **Payment Plan Template**: cash, down-payment-%-plus-installments, configurable frequency/duration, delivery-linked final payment, plus 2–3 market-shorthand presets ("60/40" etc.) — `04`§4
- **Deal/Sale** object, distinct from Reservation — `04`§3.4
- **Automatic Installment Schedule generation** (calendar-based) from a chosen Payment Plan instance — `04`§4
- **Payment/Receipt recording** (manual entry, allocated to installments, oldest-due-first default) — `04`§3.8
- **Computed Outstanding & Overdue** per deal, with standard aging buckets (Current/1-30/31-60/61-90/90+) — `04`§5
- **Mechanical Collection Forecast** (monthly/quarterly/yearly, sum of scheduled installments) — `04`§5
- **Discount/Adjustment** on a Deal before schedule generation — `04`§3.10
- **Two-leg Commission tracking** (DeveloperCommission + AgentCommissionPayout), manual state transitions acceptable for MVP (no automated triggers yet) — `04`§6
- **CEO Collection Dashboard v1**: expected/collected/outstanding/overdue this month + this quarter + this year, by project — see `09_CEO_Dashboard_Requirements.md` for the exact MVP widget set

## Should Have (fast-follow, still commercially important, target P2)

- Payment Plan **versioning** for mid-deal changes (plan swaps, restructures) — `04`§7
- **Cancellation workflow** with configurable refund computation and automatic commission clawback — `04`§3.11, §7
- **Automated commission-tranche triggers** tied to confirmed collection events (rather than manual state changes) — `04`§6
- **Risk-adjusted collection forecast** (down-weighting poor-payment-history customers) — `04`§5
- Collection forecast **by customer / by agent / by payment plan** (MVP ships by-project only) — `09`
- **PDC sub-ledger** — *only if confirmed relevant to Egypt* via `10_Business_Questions.md` Q19; do not build speculatively
- Administrative/maintenance fee schedules as distinct from unit installments — `04`§4
- Agent/team commission dashboard (expected/earned/payable/paid per agent) — `09`
- Reservation deposit → Deal down-payment reconciliation (avoid double-entry when a reservation deposit becomes the first installment)

## Later (Future/Enterprise, do not build until Should-Have is stable)

- Unit transfer with credit-forward balance — `04`§3.12
- Balloon payments, escalating/step-up installments — `04`§4
- Milestone-linked (construction-progress-triggered) schedules — `04`§4
- Multi-currency
- Full developer-inventory sync/API integrations (BRD's existing Future/Ent scope, §5.7)
- Native mobile apps (unchanged from BRD, §5.23)
- WhatsApp Business API deep integration beyond log-based activity capture (unchanged from BRD, §5.15)

## Not Needed for This Brokerage (explicitly out of scope — see `05`§4)

- General ledger / double-entry bookkeeping / chart of accounts
- Accounts payable, payroll, construction-cost accounting
- Bank reconciliation engine / payment-gateway processing (unless `10_Business_Questions.md` Q1–Q3 reveal the brokerage directly collects meaningful money at scale)
- Tax computation/filing engine
- Multi-entity consolidated financial statements
- A full developer-side project/construction-management system

---

## Why this ordering, specifically

1. **Installment generation before collection tracking before commission automation** — each depends on the one before it (`04`§1's three-layer principle: you cannot compute Outstanding without a Schedule, and you cannot automate commission triggers without reliable collection data). Building commission automation before collection tracking is stable would produce commission numbers nobody trusts — worse than not having the feature.
2. **Manual commission state transitions for MVP, automation as fast-follow** — this mirrors BRD's own existing pattern for other modules ("manual acceptable" is used repeatedly in BRD §6, §11.4) and avoids over-committing to a commission-trigger policy (`04`§6, Leg 2) before `10_Business_Questions.md` Q13–Q14 are answered by the brokerage.
3. **PDC explicitly gated behind a business-question answer** — building a PDC sub-ledger on an unconfirmed Egypt-specific assumption (`02`§11 flags this as inference, not fact) risks building the wrong thing; it's cheap to add once confirmed, expensive to have guessed wrong.
4. **Cancellation/refund logic is Should-Have, not Must-Have** — it is operationally important (documented real pain in `02`§9) but requires a policy decision from the brokerage (refund %, grace period) that doesn't yet exist; MVP can launch with a manual "mark cancelled, ops handles the refund conversation outside the system" fallback, matching BRD's own "manual/fake early" philosophy (§11.4).

## Cross-reference

Full requirement-level detail for everything in this MVP is in `07_Functional_Requirements.md`; the data model is in `08_Data_Model.md`; the CEO dashboard spec is in `09_CEO_Dashboard_Requirements.md`.
