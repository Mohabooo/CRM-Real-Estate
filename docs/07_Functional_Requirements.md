# 07 — Functional & Non-Functional Requirements

**Subject:** Detailed requirements across all modules, existing (carried from BRD v0.1, lightly referenced) and new (fully specified here, per `04`/`06`). Each functional requirement is tagged with phase (**MVP** / **P2** / **Future**) per `06_Proposed_MVP.md`. Requirements for BRD's existing modules (leads, activities, reservations, documents, notifications, audit) are **not re-derived from scratch** — they are already well-specified in BRD §5–§6 and are only extended here where the new money model touches them.

---

## A. Project & Inventory Management

| ID | Requirement | Phase |
|---|---|---|
| A1 | System shall allow creating a `Developer` record with name, contact, standard commission rate/range, and commission-payment tranche policy (Leg 1 terms, `04`§6) | MVP |
| A2 | System shall allow creating a `Project` linked to a `Developer`, extending BRD §5.7's existing Project object | MVP |
| A3 | System shall allow creating `Units` under a `Project` with price, type/size/floor/view, and status (available/reserved/sold/cancelled) — extends BRD §5.8 | MVP |
| A4 | System shall allow attaching one or more `Payment Plan Templates` to a Project or specific Unit | MVP |
| A5 | System shall warn (not block) on stale/duplicate unit entries during CSV import, consistent with BRD's existing "warn not auto-merge" dedupe philosophy (§6) | MVP |
| A6 | System shall support bulk CSV import/export of Units with price and payment-plan-template assignment | MVP |
| A7 | System shall track unit status history (already `UnitStatusHistory` in BRD Appendix A) including transitions caused by Deal/Reservation/Cancellation events | MVP |
| A8 | System shall support developer-inventory sync/API integration | Future |

## B. Payment Plan Engine

| ID | Requirement | Phase |
|---|---|---|
| B1 | System shall allow defining a Payment Plan Template with: type (cash / down-payment+installments / hybrid), down payment (% or fixed), installment count, frequency (monthly/quarterly/semi-annual/annual), duration, delivery-linked final payment (%) | MVP |
| B2 | System shall provide market-shorthand presets (e.g., "60/40," "10%+8yrs") that pre-fill B1's fields | MVP |
| B3 | System shall automatically generate an Installment Schedule (due dates + expected amounts) from a Payment Plan instance applied to a Deal, per `04`§4 | MVP |
| B4 | System shall apply a documented, consistent rounding rule when installment amounts don't divide evenly | MVP |
| B5 | System shall allow ops/finance users to manually override or hand-edit a generated schedule for a specific Deal | MVP |
| B6 | System shall support applying a Discount (fixed or %) to a Deal before schedule generation | MVP |
| B7 | System shall support milestone-linked (construction-progress-triggered) installments | P2 |
| B8 | System shall support balloon payments and escalating/step-up installment amounts | P2 |
| B9 | System shall support administrative/maintenance fee schedules as distinct from unit-price installments | P2 |
| B10 | System shall version a Payment Plan when its terms change mid-deal, preserving the history of already-paid installments (`04`§7) | P2 |
| B11 | System shall support multi-currency payment plans | Future |

## C. Deals & Reservations

| ID | Requirement | Phase |
|---|---|---|
| C1 | System shall provide a `Deal/Sale` object distinct from `Reservation`, linking Customer + Unit + Payment Plan instance + agreed price (`04`§3.4) | MVP |
| C2 | System shall allow a Reservation to be converted into a Deal, carrying forward the reservation deposit as the Deal's first installment payment where applicable | MVP |
| C3 | System shall support a per-Deal `collection_owner` flag (`developer_collects` vs. `brokerage_collects`) per `04`§2 | MVP |
| C4 | System shall support recording co-buyers on a single Deal | P2 (pending `10` Q7 confirmation) |
| C5 | System shall support a Cancellation workflow with configurable grace period and refund computation | P2 |
| C6 | System shall support Unit Transfer between Deals, optionally carrying forward a cumulative-payments credit | Future |

## D. Collections

| ID | Requirement | Phase |
|---|---|---|
| D1 | System shall allow recording a Payment/Receipt (amount, date, method, proof document) against a Deal | MVP |
| D2 | System shall allocate a Payment against Installments using an oldest-due-first default, with manual override | MVP |
| D3 | System shall compute Outstanding Balance per Deal as of any date (`04`§5) | MVP |
| D4 | System shall compute Overdue Amount per Deal with configurable aging buckets (default 0-30/31-60/61-90/90+) | MVP |
| D5 | System shall compute a mechanical Collection Forecast (sum of scheduled installments) for next month/quarter/year, filterable by project | MVP |
| D6 | System shall support Collection Forecast filtering by customer, agent, and payment plan (beyond project) | P2 |
| D7 | System shall support a risk-adjusted Collection Forecast weighted by customer payment history | P2 |
| D8 | System shall support a Post-Dated Cheque (PDC) sub-ledger with issued/deposited/cleared/bounced states | P2 (pending `10` Q19 confirmation) |
| D9 | System shall record partial payments against an installment | MVP |
| D10 | System shall support intelligent allocation of overpayments across future installments | P2 |
| D11 | System shall never allow direct edits to computed Outstanding/Overdue fields — these are always derived (`04`§1) | MVP |

## E. Commission

| ID | Requirement | Phase |
|---|---|---|
| E1 | System shall track `DeveloperCommission` (brokerage receivable) per Deal, with rate sourced from the Developer record | MVP |
| E2 | System shall track `AgentCommissionPayout` (brokerage payable) per Deal, with a configurable split rule (agent/team leader) | MVP |
| E3 | System shall support manual state transitions for both commission legs: expected → accrued → payable → paid | MVP |
| E4 | System shall support automated commission-tranche triggers tied to confirmed collection events (`04`§6) | P2 |
| E5 | System shall support commission clawback triggered by Deal cancellation, on both legs | P2 |
| E6 | System shall provide an overdue view of Developer Commission (brokerage's own AR against developers) | P2 |
| E7 | System shall support tiered/threshold commission rates (e.g., by unit count or deal value) | P2 |

## F. Dashboards & Reporting

*(Full detail in `09_CEO_Dashboard_Requirements.md`; summarized here as requirements.)*

| ID | Requirement | Phase |
|---|---|---|
| F1 | CEO dashboard shall show Expected / Collected / Outstanding / Overdue for the current month, quarter, and year | MVP |
| F2 | CEO dashboard shall show forward Collection Forecast for next month/quarter/year | MVP |
| F3 | Dashboard shall break down collections by project | MVP |
| F4 | Dashboard shall break down collections by customer and by agent | P2 |
| F5 | Dashboard shall show inventory status counts (available/reserved/sold) and payment-plan-type distribution per project | MVP |
| F6 | Dashboard shall show commission summary (expected/earned/paid, both legs) | MVP (basic) / P2 (per-agent breakdown) |
| F7 | Dashboard shall support CSV export of all money-model reports, consistent with BRD's existing export philosophy (§5.19) | MVP |
| F8 | Dashboard shall show aging-bucket breakdowns ($ and % of total receivables) | MVP |
| F9 | Dashboard shall support vintage/cohort collection-performance views (by sale-date or project-launch cohort) | P2 |

## G. Carried forward from BRD v0.1 (unchanged, referenced not restated)

Authentication & Tenancy (§5.1), User & Role Management (§5.2), CRM/Leads (§5.3), Lead Assignment (§5.4), Follow-ups & Activities (§5.5), Calendar & Tasks (§5.6), Documents Management (§5.10 — extended to cover PDC proof and Statement-of-Account documents), Notifications & Reminders (§5.11 — extended to cover overdue-installment reminders and commission-tranche-due alerts), AI Assistant Layer (§5.14 — extended per `12_Claude_Skills.md`/general AI-features note below), WhatsApp workflows (§5.15), Email workflows (§5.16), Marketing tracking (§5.18), Import/Export (§5.19), Audit logs (§5.20 — extended to cover all money-model mutations: payment recording, plan changes, commission state transitions, cancellations), Admin panel (§5.21), Billing (§5.22, vendor's own SaaS billing — unrelated to the brokerage's money model), Mobile (§5.23), Future integrations (§5.24).

**One extension worth calling out explicitly:** BRD §5.20 Audit logs must be extended to treat every money-model mutation (Payment recorded, Installment schedule regenerated, Commission state changed, Cancellation processed) as a sensitive, audited action — per `04`'s core design principle (§1, §7) that financial history must never be silently rewritten. This is a **MVP** requirement, not P2, because it is what makes the derived Outstanding/Overdue numbers trustworthy from day one.

---

## Non-Functional Requirements

| ID | Category | Requirement | Phase |
|---|---|---|---|
| N1 | Security | Money-model data (payment plans, receipts, commission) follows the same tenant-scoped RBAC as BRD §10; Finance User role (already in BRD §4) gets explicit read/write scope over Payment/Receipt and Commission objects | MVP |
| N2 | Multi-tenancy | Shared Postgres + `tenant_id` (unchanged from BRD §10); every new money-model table is tenant-scoped identically to existing tables | MVP |
| N3 | Auditability | Append-only history for Installment schedule versions, Payment records, and Commission state transitions (extends BRD §10's existing "append-only history for critical transitions" principle to the new entities) | MVP |
| N4 | Financial data integrity | Outstanding/Overdue are computed, never hand-edited (`04`§1, `D11` above); schedule regeneration always versions rather than overwrites (`04`§7) | MVP |
| N5 | Performance | Collection-forecast and dashboard aggregate queries must remain responsive at the brokerage's expected data volume (hundreds of deals, thousands of installments) without requiring a data warehouse — consistent with BRD §10's "early non-goal: full warehouse" | MVP |
| N6 | Availability | Unchanged from BRD's general posture — no new requirement introduced by the money model specifically | — |
| N7 | Data privacy | Payment/commission data is more sensitive than lead data; document-level access to Statements of Account and PDC images should be restricted beyond the general Documents module default | MVP |
| N8 | Arabic/English | Payment plan labels, installment schedules, and CEO dashboard terminology must support the same Arabic-friendly UX commitment as the rest of BRD (§1.3) — critical since "60/40"-style shorthand and EGP amounts are customer-facing | MVP |
| N9 | Currency | EGP as the sole currency for MVP (matches BRD's Egypt-first scope); multi-currency deferred to Future (`04`§4) | MVP (EGP only) / Future (multi-currency) |
| N10 | Date/time handling | Installment due dates must be stored and displayed consistently in Africa/Cairo local time with unambiguous date-only semantics (no time-of-day drift affecting "which month is this installment due in") | MVP |
| N11 | Exportability | All money-model reports exportable to CSV at minimum (matches F7); PDF polish is P2, matching BRD §6's existing reporting-workflow phasing | MVP (CSV) / P2 (PDF) |
| N12 | Scalability | Architecture should not preclude adding a proper collections/commission reporting warehouse later if deal volume grows well beyond SMB scale — but should not build one now (BRD §10 "early non-goals," reaffirmed by `05`§4) | MVP (design for), Future (build) |
