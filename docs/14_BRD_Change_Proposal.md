> **REVISED — September 2026 product-direction revision.**
> Items 5 and 9 below have been updated for the multi-commercial-model direction
> (`25_Commercial_Models.md`, `26_Commission_Rules_and_Splits.md`). All other items stand.

# 14 — BRD Change Proposal

**Subject:** A precise, section-by-section proposal of what to add/change in `BRD-Real-Estate-Brokerage-CRM-Egypt-v0.1.md` to incorporate the new business requirement. This is a **proposal**, not an edit — the original BRD file is untouched. Format: for each change, the exact location, the proposed new/replacement text, and why (cross-referenced to `01`–`10`).

---

## 1. Document control table — add a line

**Location:** the top "Document control" table.
**Add:**
| Field | Value |
|---|---|
| **Companion documents** | See `/docs/discovery/` for the September 2026 Payment/Collection/Commission Domain discovery (`01`–`14`), produced to extend this BRD with a money-model layer not covered in v0.1 |

**Why:** makes the relationship between this BRD and the new discovery documents discoverable to anyone opening the BRD cold, per the mission's instruction not to overwrite the original.

---

## 2. §1.1 Vision — extend, don't replace

**Location:** end of §1.1.
**Add sentence:** "Beyond operational discipline, the platform gives the brokerage and its management continuous visibility into what customers owe, have paid, and are late on — and into the brokerage's own commission position with each developer — without becoming a general accounting system (see `05_Recommended_Business_Model.md`)."

**Why:** §1.1's current vision statement is entirely about operational discipline (`01`§1); the new requirement is a second pillar, not a footnote, and should be visible in the vision statement itself.

---

## 3. §1.1 Strategic differentiators — add a 6th

**Location:** the numbered list of 5 differentiators.
**Add:**
> 6. **Money-model clarity**: automatic installment-schedule generation from configurable payment plans, and continuous expected/collected/outstanding/overdue visibility — a documented gap across every competing product researched (see `03_Competitive_Analysis.md`).

**Why:** `01`§1 found that none of the BRD's existing 5 differentiators mention money; `03` found this is also a genuine competitive gap in the market, making it a legitimate 6th differentiator, not just an internal requirement.

---

## 4. §4 User personas — extend the Finance User row

**Location:** §4 persona table, "Finance User" row.
**Current:** "Deposits, commission exports, payment milestones (phased)."
**Replace with:** "Payment/receipt recording, installment schedule oversight, commission tracking (both developer-receivable and agent-payable legs), collection-forecast reporting (MVP — see `06_Proposed_MVP.md` and `07_Functional_Requirements.md`)."

**Why:** the current row describes exactly the P2-deferred, thin version of this role that `01`§1 found insufficient; the new requirement makes this role's scope MVP, not phased.

---

## 5. §5.7 Projects & Property Inventory — commercial model, conditional Developer

**Location:** §5.7 "Entities" line.
**Current:** ``Project`, `Phase`, `Developer` (optional), `Listing`, `Media`.``
**Replace with:** ``Project` (carries `commercial_model`: own_inventory | brokered_inventory), `Phase`, `Developer` (**required only for brokered projects, forbidden for own-inventory projects**, see `25_Commercial_Models.md` §3), `Listing`, `Media`, `PaymentPlanTemplate` (new, see `16`§12).``

**Add to §5.7 Purpose:** "Projects carry a commercial model that determines inventory ownership, who collects customer money, whether external commission exists, and which financial dashboards apply. See `25_Commercial_Models.md`."

**Why:** the product supports both owned and brokered inventory. A developer is an external counterparty that exists only where the project actually belongs to one; a tenant selling its own stock never creates one. This replaces the earlier proposal to make `Developer` universally mandatory, which was correct only under the narrower brokerage-only reading of the product.

---

## 6. §5.8 Units Management — replace the undefined "PricePlan"

**Location:** §5.8 "Entities" line.
**Current:** ``Unit`, `UnitStatus`, `PricePlan` / payment matrix, `UnitHistory`.``
**Replace with:** ``Unit`, `UnitStatus`, `UnitHistory`. Payment/pricing terms are now modeled separately under the new Payment Plan Engine (see §5.8a below) rather than as an undefined `PricePlan` field.``

**Why:** `01`§2 (G1) identifies `PricePlan`/"payment matrix" as named but never defined anywhere in BRD v0.1. Rather than patch the definition in place, this proposal recommends **removing** it from Units and giving it a proper home as a new module (next item) — the payment plan is a relationship between Project/Unit and a reusable Template, not a Unit attribute.

---

## 7. New module — §5.8a Payment Plan & Collections Engine

**Location:** insert as a new module immediately after §5.9 Reservation Management (numbering to be finalized by whoever maintains the BRD; referred to as §5.8a here for traceability).
**Proposed text:**

> ## 5.8a Payment Plan & Collections Engine
>
> **Purpose:** Define reusable payment plans per project/unit, automatically generate customer installment schedules, and track expected/collected/outstanding/overdue money.
> **Business value:** Replaces spreadsheet-based payment tracking (documented failure mode, see `02_Brokerage_Domain_Research.md` §1, §6); gives management continuous collection visibility (the explicit new business requirement).
> **Workflows:** define payment plan template → customer selects/customizes plan on a Deal → schedule auto-generated → payments recorded and allocated → outstanding/overdue computed continuously → collection forecast reported.
> **Entities:** `PaymentPlanTemplate`, `CustomerPaymentPlan`, `Installment`, `Payment`, `PaymentAllocation`, `Discount`, `PostDatedCheque` (P2). Full detail: `08_Data_Model.md`.
> **Actors:** Sales Agent (selects plan), Finance User (records payments), Branch Manager/Owner (views forecasts).
> **Dependencies:** `Deal` object (see §5.9a below), `Developer` entity.
> **Phase:** **MVP-Core** for template/schedule-generation/payment-recording/outstanding-overdue; **P2** for plan versioning, PDC, milestone-linked schedules.
> **Complexity:** Medium–High.
> **Strategic differentiator** (see updated §1.1).

**Why:** this is the module BRD v0.1 simply does not have (`01`§1's central finding) — it needs to exist as a peer to Reservation Management and Units Management, not be squeezed into either.

---

## 8. New object — §5.9a Deal / Sale (insert after Reservation Management)

**Proposed text:**

> ## 5.9a Deal / Sale
>
> **Purpose:** Represent the confirmed commercial transaction, distinct from a Reservation (a time-bound hold). A Reservation converts into a Deal when terms are agreed.
> **Business value:** Separates the low-stakes, frequently-abandoned Reservation lifecycle from the contractually consequential Deal lifecycle (cancellation, refund, commission) — see `04_Payment_Collection_Domain.md` §3.4 for the rationale.
> **Workflows:** Reservation confirmed → Deal created with agreed price/plan → Payment Plan instantiated → (later) Cancellation or Unit Transfer if applicable.
> **Entities:** `Deal`, `Cancellation` (P2), `UnitTransfer` (Future).
> **Actors:** Sales Agent, Branch Manager (approval if required), Finance User.
> **Dependencies:** Reservation, Unit, Customer, Payment Plan Engine (§5.8a).
> **Phase:** **MVP-Core**.
> **Complexity:** Medium.

**Why:** `01`§2 (G3) and `04`§3.4/`05`§3 establish that BRD's Reservation object cannot safely carry both meanings; the current Reservation-status-enum-only design (§5.9) has no object for what happens after "confirmed."

---

## 9. §5.17 Commission basics — replace entirely

**Location:** §5.17.
**Current:** phased P2 minimum, "advanced engine Future/Ent," "manual early: export to sheet."
**Replace with:**

> ## 5.17 Commission Management
>
> **Purpose:** Track the brokerage's commission receivable from developers (Leg 1) and payable to agents/team leaders (Leg 2), reflecting that Egyptian developer commission is paid in tranches over 6–18 months, not lump sum (see `02_Brokerage_Domain_Research.md` §7, §11).
> **Business value:** Commission disputes are a documented, litigation-relevant risk (`02`§7); manual tracking cannot represent tranche-based payment accurately.
> **Workflows:** commission rule set per developer → Deal generates both commission legs → manual (MVP) or automated (P2) state transitions (expected→accrued→payable→paid) → clawback on cancellation (P2).
> **Entities:** `CommissionRule` (already in Appendix A, now fully defined — direction, scope, configurable basis and trigger), `Commission` (new — one entity with a `direction`, replacing any two-leg assumption), `CommissionAdjustment` (new).
> **Phase:** **MVP-Core** configurable rule engine with manual state transitions; **P2** automated triggers, tiering, tranche schedules.
> **Complexity:** Medium (MVP) → High (P2 automation).
> **Note:** external (inbound) commission exists only for brokered-inventory projects. Own-inventory projects generate internal (outbound) commission only. See `26_Commission_Rules_and_Splits.md`.

**Why:** `01`§4 (G6) and `04`§6 together establish that "export to sheet" cannot represent the documented tranche-based reality, and that this is a regulated, dispute-prone relationship (`02`§7) that deserves MVP-level (not P2-minimum) attention.

---

## 10. §9 Reporting & dashboard requirements — add a Collections subsection

**Location:** §9, after the existing Owner/Manager/Agent/KPI bullets.
**Add:**

> - **Collections (new, MVP):** expected / collected / outstanding / overdue — this month, this quarter, this year; forward collection forecast; aging-bucket breakdown; by project (MVP), by customer/agent/plan (P2). See `09_CEO_Dashboard_Requirements.md` for full specification.
> - **Commission (new, MVP basic / P2 detailed):** expected/accrued/paid for both commission legs.

**Why:** §9 currently has zero money-related content (`01`§2 coverage map); this is the direct dashboard consequence of adding §5.8a and §5.17.

---

## 11. §11 MVP definition — reference the new proposed MVP

**Location:** §11.1–§11.2.
**Add note at the top of §11:** "See `06_Proposed_MVP.md` for the September 2026 revision incorporating the Payment Plan & Collections Engine (§5.8a), Deal object (§5.9a), and Commission Management (§5.17 revised) into the Recommended MVP. Sections 11.1–11.2 below are retained as originally written for the CRM-only scope; they are additive to, not replaced by, the new money-model MVP items."

**Why:** avoids silently rewriting the BRD's existing (still valid) CRM-core MVP definition, while pointing readers to the new companion document — consistent with the mission's "do not overwrite" instruction applied at the section level, not just the file level.

---

## 12. Appendix A — Suggested entities: add the new set

**Location:** Appendix A entity list.
**Add:** ``Deal, Developer (promote from optional), PaymentPlanTemplate, CustomerPaymentPlan, Installment, Payment, PaymentAllocation, Discount, Cancellation, UnitTransfer, DeveloperCommission, AgentCommissionPayout, PostDatedCheque``, with a footnote: "Full attribute/relationship detail and phase tags: `08_Data_Model.md`."

**Why:** direct extension of the existing appendix; `CommissionRule` already exists in this list and is now defined rather than replaced.

---

## 13. Appendix B — ERD concepts: extend the described chain

**Location:** Appendix B.
**Current:** "Reservation links Lead + Unit with status timeline."
**Add after it:** "A confirmed Reservation converts to a Deal, which links Customer + Unit + a Payment Plan instance. The Payment Plan instance generates Installments; Payments are recorded against Installments via PaymentAllocation. Outstanding and Overdue are computed, not stored. Each Deal generates two Commission records (Developer-owed, Agent-owed). Full diagram: `08_Data_Model.md` §2."

**Why:** the current ERD description stops exactly where the new requirement begins (`01`§2 coverage map) — this is the minimal addition to make the appendix internally consistent with the new modules.

---

## Summary of proposed changes

| BRD location | Change type | Driven by |
|---|---|---|
| Document control | Add companion-doc reference | Traceability |
| §1.1 Vision | Extend | `01`§1 |
| §1.1 Differentiators | Add 6th item | `01`§1, `03` |
| §4 Finance User | Extend/reclassify to MVP | `06`, `07` |
| §5.7 Entities | Add commercial model; Developer conditional; add PaymentPlanTemplate | `25`§3 |
| §5.8 Entities | Remove undefined PricePlan | `01`§4 G1 |
| New §5.8a | New module: Payment Plan & Collections Engine | `04`, `08` |
| New §5.9a | New object: Deal/Sale | `01`§4 G3, `04`§3.4 |
| §5.17 | Replace Commission basics entirely with the configurable engine | `26` |
| §9 | Add Collections + Commission dashboard bullets | `09` |
| §11 | Add cross-reference to new MVP doc | `06` |
| Appendix A | Add 13 new/promoted entities | `08` |
| Appendix B | Extend ERD description | `08`§2 |

No existing BRD content is deleted except the single undefined `PricePlan` reference (item 6), which is replaced by a properly specified module rather than left as an unresolved placeholder.
