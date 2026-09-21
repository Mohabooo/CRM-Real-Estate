# 09 — CEO / Owner Dashboard Requirements

**Subject:** What a brokerage CEO/owner needs to see, prioritized. Grounded in executive-dashboard research and AR/collections dashboard patterns (see source list) plus the money model in `04`. The organizing test used throughout: *"If I open this system every morning, what do I need to know in 30 seconds?"*

---

## 1. Design principles (from research)

**[DOCUMENTED]** Executive dashboards should surface **outcome metrics** (revenue, collections, pipeline value) rather than **activity metrics** (calls made, emails sent) — activity metrics belong on agent/manager dashboards, not the CEO's. [HubSpot] The "5-second rule": a CEO should grasp overall business health at a glance, without drilling in. [DashThis]

**[DOCUMENTED]** A recurring structure across real executive dashboards: a compact **top strip of headline numbers**, followed by **domain sections** (Sales / Finance / Customers / Ops), reviewed at monthly cadence for trend, with drill-down available but not forced. [Perceptive Analytics; DashThis]

**[INFERENCE]** Combining this with the real-estate/AR-specific research below, the recommended shape for this brokerage's CEO dashboard is: a **top strip of 5–8 headline numbers**, then domain sections for **Sales, Collections, Inventory, Customers, Agents, Projects** — not documented verbatim anywhere as a single real-estate CEO dashboard, but a direct synthesis of the executive-dashboard pattern with the domain content below.

---

## 2. MVP dashboard — the minimum executive view

Prioritized per `06_Proposed_MVP.md`; everything below must be answerable from data the MVP data model (`08`) already captures.

### Top strip (headline numbers)
1. **Expected collections this month** (Σ installments due this month, all active deals)
2. **Collected this month** (Σ payments received this month)
3. **Outstanding balance** (total, as of today)
4. **Overdue amount** (total, as of today, with aging-bucket breakdown available on click)
5. **Active deals** count + total contracted value
6. **Units available / reserved / sold** (current snapshot)

All six map directly to explicit asks in the mission's "Management/CEO Visibility" section and are computable from MVP-phase entities in `08` with no dependency on P2/Future entities.

### Sales section
- Active deals, closed deals (period), reservation pipeline (count + value) — extends BRD §9's existing funnel/pipeline reporting (already MVP-Rec in BRD) with money-model values attached.
- **[DOCUMENTED — Sell.Do]** Sales by project.

### Collections section (the new, core addition)
- Expected / Collected / Outstanding / Overdue — **this month, this quarter, this year** (three time horizons, per the mission's explicit ask), never shown as a single blended number (`04`§5's core principle).
- **Forward collection forecast** — next month, next quarter, next year (mechanical sum for MVP, per `04`§5).
- **Aging-bucket breakdown** ($ and % of total receivables) — standard AR-dashboard practice. [HighRadius; Chargebee]
- Collections **by project** (MVP); by customer/agent/plan deferred to P2 (`06`).

### Inventory section
- Unit counts by status (available/reserved/sold) per project.
- Payment-plan-type distribution (which plans are actually being used — explicit new-requirement ask).

### Agents / Commission section
- Commission summary: expected / accrued / paid, **both legs** (Developer→Brokerage, Brokerage→Agent) — basic totals for MVP; per-agent breakdown is P2.

### Projects section
- Sales value and collections by project (which projects generate the most **expected** vs. most **actual** collections — explicitly distinguished per the mission brief).

---

## 3. P2 dashboard additions

**[DOCUMENTED — HighRadius]** Standard AR/collections KPIs worth adding once MVP collections data has enough history to be meaningful:
- **DSO (Days Sales Outstanding)** = (Total A/R ÷ Total credit sales) × Days in period.
- **Collection Effectiveness Index (CEI)** = (Cash collected ÷ Total outstanding receivables) × 100.
- **Average Days Delinquent (ADD)**.
- **Promise-to-pay rate** (leading indicator, requires an ops workflow to capture promises — not just payment data).
- **% On-time payments.**

**[DOCUMENTED — Abbacus, loan-servicing pattern]** **Vintage/cohort analysis** — collection performance grouped by sale-date or project-launch cohort, and **roll-rate analysis** — tracking how many "current" accounts moved into "30 days late" this period. Both are P2: they need enough historical data (multiple cohorts, multiple periods) to be useful, which an MVP-stage brokerage won't yet have.

**[DOCUMENTED — Sell.Do]** At-risk deal flagging (deals inactive beyond a threshold, flagged by value), segment concentration analysis (e.g., "% of pipeline from one customer segment/project" — a concentration-risk signal), rep/agent leaderboard by revenue.

**Collections filterable by customer, by agent, by payment plan** (MVP ships project-level only, per `06`/`07` D6).

**Per-agent commission breakdown**: quota attainment-style view (expected/earned/paid per agent), echoing Everstage's documented commission-report structure (`04`§6). Pending/clawback commission with audit trail, matching Everstage's documented pattern.

---

## 4. Explicitly deferred (Future / not needed for this brokerage's MVP)

- Full BI/warehouse-backed analytics (BRD §5.13 already phases this Future — unchanged).
- Investor-facing metrics (payback period, ROI, IRR, NOI) — these are **developer-side** KPIs per `05`§2's brokerage-vs-developer boundary, not this system's job.
- Market-level absorption-rate/months-of-inventory metrics — a market-analysis concept, not an operational brokerage metric; out of scope unless requested.

---

## 5. Cancellation/refund and unit-transfer visibility (a specific gap worth flagging)

**[DOCUMENTED gap]** Neither of the two most detailed off-plan CRM vendor pages reviewed (Sell.Do, Linz Technologies) surfaces cancellation/refund/transfer as a documented dashboard feature — this is a genuine gap across the competitive set, not just in BRD v0.1. Since cancellations directly affect Outstanding, Overdue, and Commission clawback (`04`§7), the CEO dashboard should — once cancellation workflow ships (P2, `06`) — show a **cancelled-deals-this-period** count/value and its downstream commission-clawback impact, so cancellations don't silently distort collection-forecast accuracy without explanation.

---

## Source list

- HighRadius — Top 10 Collections KPIs & Performance Metrics: https://www.highradius.com/resources/Blog/10-collections-performance-metrics-and-kpis/
- Chargebee — Receivables AR Analytics Dashboard: https://www.chargebee.com/receivables/ar-analytics-dashboard/
- Abbacus Technologies — How to Build a Loan Portfolio Management Dashboard: https://www.abbacustechnologies.com/how-to-build-a-loan-portfolio-management-dashboard/
- Sell.Do — Real Estate Sales Reporting & Analytics: https://www.sell.do/real-estate-crm/reports-dashboards
- Sell.Do — Real Estate CRM Dashboard: What to Track in 2026: https://www.sell.do/blog/real-estate-crm-dashboard-analytics-advantage
- Linz Technologies — Real Estate Developer CRM: https://www.linztechnologies.com/real-estate-crm-for-developers
- Norada Real Estate — Absorption Rate and Months of Inventory: https://www.noradarealestate.com/blog/absorption-rate-and-months-of-inventory/
- Phoenix Strategy Group — 5 KPIs Every Real Estate Developer Dashboard Needs: https://www.phoenixstrategy.group/blog/real-estate-developer-dashboard-kpis
- Databox — 17 KPIs and Metrics for a Real Estate KPI Dashboard: https://databox.com/real-estate-metrics-dashboard
- DashThis — The Perfect CEO Dashboard for Executives: https://dashthis.com/ceo-dashboard/
- Perceptive Analytics — Top 11 Executive Dashboards Every CEO and COO Needs: https://www.perceptive-analytics.com/top-executive-dashboards/
- HubSpot — Sales performance dashboard: 13 examples + how to build your own: https://blog.hubspot.com/sales/sales-dashboard
- Everstage — Creating the Ultimate Sales Commission Report: https://www.everstage.com/sales-commission/sales-commission-report
- MoneyTree Realty — What happens if a buyer cancels a property deal: https://moneytreerealty.com/blog/what-happens-if-buyer-cancels-a-property-deal
- Gulf News — Recovery schemes: A silver lining: https://gulfnews.com/business/property/recovery-schemes-a-silver-lining-1.864559
- Respicio & Co. — Real Estate Developer Cancellation Notice Without Refund Plan (Maceda Law): https://www.respicio.ph/commentaries/real-estate-developer-cancellation-notice-without-refund-plan
- NetSuite — 33 Real Estate Metrics to Track (candidate for follow-up, not deep-fetched): https://www.netsuite.com/portal/resource/articles/business-strategy/real-estate-metrics.shtml
