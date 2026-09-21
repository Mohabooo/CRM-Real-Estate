# 01 — BRD Gap Analysis

**Subject:** Gap analysis between BRD v0.1 ("AI-Enabled Real Estate Brokerage Operations Platform," Egypt), real-world brokerage operational needs, and the new business requirement (projects/inventory, payment plans, deal-level collections, CEO collection visibility).

**Method:** BRD v0.1 was read in full (all 15 sections + appendices). Every claim below is either (a) cited to a specific BRD section, (b) cited to external research in `02_Brokerage_Domain_Research.md` / `03_Competitive_Analysis.md` / `04_Payment_Collection_Domain.md`, or (c) explicitly marked **[ASSUMPTION]** / **[INFERENCE]**. Nothing here should be read as a decision — it is the evidence base for the decisions in `05`–`08`.

---

## 1. What the BRD is actually built for

BRD v0.1 positions the product as a **"Brokerage Operations Platform"** whose wedge is *lead ownership, SLA discipline, and inventory/reservation hygiene* (§3.5, §1.6). Its own stated differentiators (§1.1 "Strategic differentiators") are entirely about **operational discipline**, not **financial/collections depth**:

1. Brokerage-native objects (unit, project, reservation, visit)
2. Operational closure (ownership, SLAs, stale detection)
3. Egyptian UX/WhatsApp reality
4. Fast onboarding
5. Assistive AI

Nowhere in the vision (§1), the business problems list (§2), or the module catalog (§5) does the BRD name **payment plans, installments, collections, receivables, or commission** as a strategic pillar. The one glancing mention of money is:

- §5.8 Units Management: `PricePlan` / "payment matrix" listed as a conceptual entity, phased **P2** for "advanced matrices," with **no definition of what a payment matrix contains**.
- §5.9 Reservation Management: "payments depth" is explicitly deferred to **P2** with no object model.
- §5.17 "Commission basics": phased **P2 minimum**, "advanced engine Future/Ent," and explicitly says the MVP fallback is **"export to sheet."**
- §5.22 Subscription/Billing: this is the *vendor's own SaaS billing* (charging the brokerage for the software), not the brokerage's collection of money from its customers — a different concept entirely, and easy to conflate if read quickly.

**Conclusion:** BRD v0.1 is a coherent, well-specified **lead-to-reservation operations CRM**. It is silent — not thin, silent — on everything the new business requirement is actually about: multi-scheme payment plans, automatic installment-schedule generation, per-customer expected/collected/outstanding/overdue tracking, and a real commission engine. This is not a flaw in the BRD's own logic (it was scoped that way on purpose), but it means the new requirement is not a "deepen an existing module" change — it is a **new domain layer** the BRD does not model at all.

---

## 2. Section-by-section coverage map

| BRD section | Covers | Does NOT cover (relevant to new requirement) |
|---|---|---|
| §5.7 Projects & Property Inventory | Project, Phase, Developer (optional), Listing, Media; CSV import; MVP-Core | Multiple payment plans *per project*; project-level commercial terms from the developer (the brokerage's own commission rate with that developer) |
| §5.8 Units Management | Unit CRUD, status transitions, `PricePlan`/payment matrix (undefined), `UnitHistory`; MVP-Core simplified, "advanced matrices P2" | What a payment plan actually *is* (down payment %, installment count/frequency, duration); multiple simultaneous plans per unit; automatic schedule generation |
| §5.9 Reservation Management | Hold → deposit → docs → confirmed/cancelled/expired; MVP-Rec; "payments depth P2" | Reservation deposit ≠ full payment-plan tracking; no object for the *contract/sale* that follows a confirmed reservation; no link from Reservation to an installment schedule |
| §5.10 Documents Management | ID/contract/developer-form uploads | Financial documents specifically (receipts, PDCs, statements of account) as structured data rather than opaque files |
| §5.17 Commission basics | Named as a module, phase P2, "manual: export to sheet" | Who pays whom (developer→brokerage vs. brokerage→agent) as two distinct legs (§04); commission tied to collection status vs. deal closure; splits/tiers; clawback on cancellation |
| §9 Reporting & dashboards | Funnel, sources, stale leads, SLA breaches, unit aging | Nothing about money: no expected/collected/outstanding/overdue, no collection forecast, no commission dashboards |
| Appendix A (entities) | `Tenant, User, Membership, Role, Branch, Lead, Contact, LeadStage, LeadSource, Activity, Task, Project, Phase, Unit, UnitStatusHistory, Reservation, ReservationDocument, Document, Notification, ImportJob, AuditEvent, Campaign, CommissionRule (later), Subscription (later), AiJob/AiUsageLog (later)` | No `Deal/Sale`, `PaymentPlan`, `Installment`, `Payment/Receipt`, `Discount`, `Cancellation`, `UnitTransfer`, `Commission` (only a bare, undefined `CommissionRule` placeholder), `Developer` as a first-class entity with commercial terms |
| Appendix B (ERD concepts) | Tenant roots all data; Lead↔Activity; Lead↔Unit/Project (optional link); Unit↔Project; Reservation↔Lead+Unit | No financial chain at all — the ERD stops at "Reservation links Lead + Unit with status timeline" |

---

## 3. Gap analysis: three lenses

### Lens 1 — What the BRD currently says
A lead-to-reservation operations platform: capture demand, assign it, keep agents accountable via activities/SLAs, hold inventory hygiene, and give managers pipeline/SLA visibility. Money is out of scope by design until P2, and even then only as "commission basics" and an undefined "payment matrix."

### Lens 2 — What a real brokerage actually needs operationally
Per `02_Brokerage_Domain_Research.md`, real brokerages (especially Egyptian ones selling primary/off-plan stock) live and die on:
- Not losing/duplicating leads and not overselling units (BRD covers this reasonably well).
- **Knowing, at any moment, what a given customer owes, has paid, and is late on** — the BRD has zero coverage.
- **Getting paid their own commission from the developer, correctly and on time**, and paying agents/team leaders their internal split — the BRD defers this to "export to sheet."
- Egypt-specific payment mechanics: post-dated cheques, multi-year installment plans (4–16 years observed), milestone/delivery-linked payments, maintenance-fee timing — none of this is in the BRD's vocabulary at all.
- Handling deal changes: discounts, plan changes, cancellations with partial refunds, unit transfers — the BRD's Reservation object has statuses (`confirmed/cancelled/expired`) but nothing downstream of "confirmed" (no contract, no schedule, no refund logic).

### Lens 3 — What the new business requirement explicitly asks for
Project/unit inventory with **multiple payment plans**, automatic **installment schedule generation**, per-deal **expected / collected / outstanding / overdue**, and CEO-level **collection forecasting** (monthly/quarterly/yearly, by project/customer/agent/payment plan) plus **commission visibility**. This maps almost one-to-one onto the entities the BRD's Appendix A does *not* have: `Deal`, `PaymentPlan`, `Installment`, `Payment`, `Commission`.

**Net gap statement:** the new requirement is not an extension of §5.8/§5.9/§5.17 — it is a **new domain (the "money model")** that must be designed from scratch and then *attached* to the BRD's existing Project/Unit/Reservation objects. See `08_Data_Model.md` for the concrete entity set and `14_BRD_Change_Proposal.md` for exactly what to insert into the existing document.

---

## 4. Specific gaps introduced by the new requirement (summary table — full detail in `07_Functional_Requirements.md`)

| # | Existing BRD coverage | What's missing | Why it matters | Phase | Complexity | Dependencies |
|---|---|---|---|---|---|---|
| G1 | `PricePlan`/"payment matrix" named, undefined (§5.8) | A real Payment Plan *template* object: type (cash / down-payment % + installments / milestone-linked), frequency, duration, down-payment %, escalation rules | Without this, "select a payment plan" is meaningless — there's nothing to select | MVP | Medium | Unit, Project |
| G2 | None | Automatic **Installment** generation from a chosen plan + unit price | This is the single most-requested new capability ("system should calculate the resulting payment schedule automatically") | MVP | Medium–High | G1 |
| G3 | Reservation has a status enum only | A **Deal/Sale** object distinct from Reservation, representing the confirmed commercial transaction with agreed terms | Reservation = a hold; Deal = a contract. Conflating them breaks refund/cancellation logic later | MVP | Medium | Reservation, Customer, Unit |
| G4 | None | **Payment/Receipt** ledger (actual money received) separate from the Installment schedule (expected money) | Without this separation you cannot compute "collected vs. expected" — the single most-requested CEO metric | MVP | Medium | G2 |
| G5 | None | Derived **Outstanding** and **Overdue** views (not stored, computed) | Explicitly demanded ("must NOT be treated as the same metric") | MVP | Low (once G2–G4 exist) | G2, G4 |
| G6 | §5.17 "Commission basics," P2, manual export | A real **Commission** model with two legs (developer→brokerage, brokerage→agent), tiering/splits, and a state machine (expected/accrued/payable/paid/clawback) | Egypt research shows commission is paid in **tranches over 6–18 months**, not lump sum — a static "export to sheet" cannot represent this | MVP (basic) / P2 (splits, clawback automation) | Medium–High | G3, Developer entity |
| G7 | None | **Post-dated cheque (PDC)** sub-ledger | MENA-standard collection instrument (documented for UAE, inferred for Egypt — see `02`); a "payment received" flag is not enough, a PDC has its own issued→deposited→cleared/bounced lifecycle | P2 (MVP can start with a generic "payment method" field and add PDC states once confirmed with the business — see `10_Business_Questions.md`) | Medium | G4 |
| G8 | None | **Discount/Adjustment** on a deal's payment plan | Common in negotiation; changes the schedule after generation | P2 | Medium | G1, G2 |
| G9 | Reservation status `cancelled` exists, no downstream logic | **Cancellation** workflow: grace period, refund computation, unit release, commission clawback | Documented as a real operational failure mode in Egypt (disputed partial refunds); legally load-bearing in other jurisdictions (Maceda Law precedent, Philippines) — confirms this needs real logic, not a status flip | P2 | Medium–High | G3, G4, G6 |
| G10 | None | **Unit transfer** (swap a customer from one unit to another, carrying forward payments made) | Documented UAE practice; policy varies by developer — must be configurable | Future | Medium | G3, G4, G9 |
| G11 | §9 dashboards cover funnel/SLA/stale leads only | **Collection forecast dashboards** (monthly/quarterly/yearly; by project/customer/agent/plan) + **CEO commission visibility** | This is the explicit new-requirement ask (§"Management/CEO Visibility") | MVP (basic) / P2 (forecast, cohort/vintage views) | Medium–High | G2, G4, G5, G6 |
| G12 | `Developer` listed as "optional" entity (§5.7) | `Developer` as a **required, first-class** entity carrying commercial terms (commission rate, payment tranche policy) once payment plans exist | The brokerage sells *developer* inventory; developer terms drive both the payment plan template and the commission rule | MVP | Low–Medium | Project |

---

## 5. What the BRD gets right and should NOT be rebuilt

To avoid over-correcting: the BRD's lead/activity/reservation/audit/notification scaffolding (§5.1–§5.6, §5.10–§5.11, §5.19–§5.20) is sound, Egypt-appropriate, and should be **kept as-is**. The new money-model layer should be designed as an **attachment** to `Unit`/`Reservation`/`Project`, not a replacement of the CRM core. This distinction matters for scope control in `06_Proposed_MVP.md` — the risk is not "the BRD is wrong," it's "the BRD is silent on the one thing the brokerage just said is the actual point."

## 6. Assumptions made in this analysis

- **[ASSUMPTION]** The new requirement is additive to BRD v0.1, not a replacement of its lead/reservation scope — confirmed by the mission brief's framing ("do NOT force the new requirements into the existing BRD terminology if insufficient" implies extension, not replacement).
- **[ASSUMPTION]** "The brokerage" in the new requirement is the same entity described in BRD §3.6 (SMB Egyptian brokerage, primary + resale mix) — not a developer. This is tested explicitly in `10_Business_Questions.md` (Q1) and is the single most consequential unresolved assumption in this entire discovery.
