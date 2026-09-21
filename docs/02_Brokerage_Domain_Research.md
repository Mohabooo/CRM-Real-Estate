# 02 — Brokerage Domain Research

**Subject:** How real-estate brokerages actually operate, and the recurring operational pain points that a purpose-built system should address. Sourced from web research (subagent research pass, September 2026); every claim is tagged **[DOCUMENTED]** (primary/official/trade source), **[NEWS SOURCE]** (journalism/press), or **[INFERENCE]** (no direct source found — reasoned conclusion, flagged explicitly). Full source list at the end.

---

## 1. Excel dependency

**[DOCUMENTED]** Brokerages running on spreadsheets report a consistent failure pattern: leads "sit untouched because a manager can't see them in someone's private sheet"; handovers fail when an agent leaves because "the next person has nothing to pick up"; "duplicates multiply, because the same client is recorded differently across three sheets"; follow-ups are missed because "the reminder lived in one person's head"; and the business "can't be measured, because there's no single source of truth to report from." [PropSpace]

**[DOCUMENTED]** At scale, spreadsheet-based financial tracking breaks down concretely — one cited real-estate cash-management example describes a firm "manually managing a $2 billion, 13-week forecast in a spreadsheet — with a variance of 10%." [MRI Software]

**[DOCUMENTED]** Manual commission calculation via spreadsheets becomes unmanageable once splits involve listing agents, buyer agents, team leaders, and referral fees — "manual methods cannot scale this level of complexity without errors," and audit-trail/compliance needs become unworkable. [Retyn.ai]

**Relevance to this project:** Excel dependency is the direct predecessor state this CRM replaces for *both* the existing BRD scope (leads) and the new scope (payment plans/collections/commission) — the new requirement is, in effect, "stop running the money side of the business in a spreadsheet too."

---

## 2. WhatsApp dependency

**[INFERENCE]** No primary source (forum post, case study, press article) was found that documents WhatsApp-specific lead-leakage failure modes for real-estate brokerages by name. This is a widely believed, anecdotally universal risk in MENA brokerages (informal customer-truth living in personal chats, lost on agent turnover, unreconstructable history — as BRD v0.1 §1.5/§2 itself already asserts), but this research pass could not independently corroborate it with a citable source. **Recommendation:** treat the BRD's own WhatsApp-dependency claims as pre-existing product knowledge, not as newly re-verified here; if a precise, citable claim is needed for a pitch deck or investor doc, this should come from practitioner interviews, not secondary web research.

---

## 3. Lost/leaked leads and duplicate customers

**[DOCUMENTED]** Speed-to-lead research (compiled across MIT/InsideSales.com, NAR, Inman's Real Estate Technology Survey, Roof AI) is unusually specific: average agent response time is **917 minutes (over 15 hours)** against a recommended 5-minute threshold; leads contacted within 5 minutes are **21x more likely to qualify**; contact probability is up to **100x** higher within 5 minutes vs. 30 minutes; **78% of buyers work with the first agent who responds**; only **0.1%** of inbound leads receive a response within 5 minutes; **41% of major brokerages never respond** to inquiries at all; each missed lead is estimated at **$7,500+ in potential lost commission**. [Hyperleap]

**[DOCUMENTED]** Duplicate-lead handling is treated as a standard, unavoidable CRM data-hygiene problem in real estate — vendors build fuzzy-match dedupe rules, required-field guardrails, and monthly audit SOPs specifically because agencies accumulate the same client recorded multiple times across channels/agents. [TryRealy; KeeTechnology]

**Relevance:** this validates BRD §5.3/§5.4's "dedupe = warn, not auto-merge" MVP decision, and validates the strategic emphasis on ownership/SLA discipline as a real, quantifiable problem (not a nice-to-have).

---

## 4. Unit availability conflicts / overselling

**[INFERENCE]** No source directly documents a "brokerage oversold/double-booked the same unit" incident by name. However, dedicated real-estate CRM/inventory vendors build entire modules (unit-status dashboards, "inventory at a glance," reservation-locking) whose sole purpose is preventing exactly this — the existence of purpose-built tooling is itself indirect evidence the problem is real and common enough to justify it. [Zoho/Pyramidbits; Builderopedia; SMART ERP SUITE]

---

## 5. Incorrect / stale pricing and payment plans

**[INFERENCE]** No source directly documents a broker quoting a stale price or expired payment plan to a customer. This is a plausible and connected risk given that Egyptian developer offers are explicitly **time-limited** ("most discounts and reduced down payments are available only for a limited period" — [The Property, NAC Egypt Offers]); a brokerage working from a static sheet or memory would be structurally exposed to this. Flagged as inference, not fact.

---

## 6. Payment follow-up chaos, collections difficulty, overdue tracking

**[NEWS SOURCE — Egypt]** Egypt's real-estate delivery crisis has produced buyers who kept paying monthly installments to developers even while construction stalled for years — one buyer had paid **~80% of a ~4M EGP unit** and was still being billed **three years past the promised delivery date**. Experts interviewed recommend escrow accounts (funds released only against verified construction milestones) and specialized real-estate courts, implying there is currently no independent verification layer between what's owed, what's paid, and what's actually delivered. [Al Manassa]

**[NEWS SOURCE — Egypt]** A separate investigation documents developers diverting new-project down payments to fund unrelated land purchases, demanding undocumented extra fees (up to **100,000 EGP / ~$2,062 per unit**) beyond contract terms, and offering only partial refunds (a **15% deduction**) instead of full cancellation refunds. [Zawia3]

**[DOCUMENTED]** General billing-domain framing, directly relevant to the data model: "an outstanding balance is the amount that's been charged but not yet paid" and only becomes **overdue** "after the payment deadline passes" — a distinction informal brokerage tracking (spreadsheets, WhatsApp reminders) routinely blurs. [Stripe]

**Relevance:** this is the strongest evidence in the entire research pass that "expected vs. collected vs. outstanding vs. overdue" must be four genuinely distinct, independently computable fields in the data model — see `04_Payment_Collection_Domain.md`.

---

## 7. Commission disputes

**[DOCUMENTED]** "Commission miscalculations can be detrimental to your agency's reputation, finances and even open up doors to litigation issues"; commission disputes are cited as "among the top reasons high-producing agents change brokerages." [Retyn.ai]

**[DOCUMENTED]** Commission disputes are a recognized litigated category both between agents and brokerages and between agents (procuring-cause disputes, commission-sharing disputes). [Colibri Real Estate; Mashian Law Group]

**[DOCUMENTED — Egypt]** Brokers selling for developers face "delayed commissions (depending on developer cash flow)" as an acknowledged real risk, with guidance to formally contract on "commission rates and payout schedules" upfront. [Egyptian Real Estate Platform Blog]

**[DOCUMENTED — Egypt, regulatory]** Egypt introduced a new brokerage-licensing regime (via GOEIC, under the Ministry of Investment and Foreign Trade) with tiered capital/registration categories (Category A: >100M EGP annual transaction volume requires 1M EGP capital, down to Category D: ≤10M EGP requiring 20,000 EGP) and four defined broker types (seller, buyer, dual, rental) — a direct regulatory response to a previously loosely-regulated, dispute-prone market. [Property Finder EG]

**[NEWS SOURCE — Egypt]** Historically brokers "considered themselves partners in mega projects, not just promoters," pushing commission rates that new regulatory amendments have since **capped at up to 5% of unit price**; brokers are estimated to handle **70–80% of developer sales volume**, creating heavy mutual dependency (and leverage disputes) between developers and brokerage networks. [INVEST-GATE]

**Relevance:** commission is not a "nice to have P2 module" — it is a documented, regulated, litigation-relevant part of the Egyptian brokerage relationship with developers. This directly informs `05_Recommended_Business_Model.md` and elevates commission tracking above BRD v0.1's current "P2 / export to sheet" treatment.

---

## 8. Management visibility / forecasting gaps

**[DOCUMENTED]** Real-estate cash-management visibility gaps are described concretely: "a treasurer might need 20 different passwords, log in to 20 different bank portals, and export 60 different bank statements" just to see current cash position, alongside the $2B/13-week manual forecast example (10% variance) cited in §1. [MRI Software]

**[DOCUMENTED]** Commercial real-estate brokerage sales cycles run long (multi-month/multi-year), which compounds pipeline/expectation-tracking difficulty absent structured tooling. [Real Estate Business Review]

**[INFERENCE]** No source ties an Egyptian brokerage's management-visibility gap to a named failure case, but INVEST-GATE's recommendation for "joint databases that list housing for sale" with pricing/commission info as a *fix* for the fragmented Egyptian market strongly implies the current absence of a shared, real-time data layer across branches/agents.

---

## 9. Deal changes: discounts, cancellations, refunds, unit transfers

**[NEWS SOURCE — Egypt]** Documented developer malpractice patterns: repossessing and reselling units already sold to a different buyer; offering partial refunds "with 15% deductions instead of full returns"; demanding undocumented "lifetime maintenance fee" charges at handover via coercive paperwork. [Zawia3]

**[DOCUMENTED — Egypt, legal]** Missed/late installment payments in Egypt trigger, under Civil Code No. 131/1948, Real Estate Registration Law No. 114/1946, and Consumer Rights Protection Law No. 67/2006: interest on overdue amounts, fixed late fees, a grace period before penalties activate, a **formal default notice requirement** before further action, and potential eviction/legal recovery as a last resort. Exact percentages are not specified by this source. [Andersen Egypt]

**[DOCUMENTED — international precedent]** The Philippines' Maceda Law (RA 6552) is the most concrete documented cancellation/refund framework found anywhere in this research: grace periods scale with tenure of payments (minimum 60 days under 2 years paid; one month of grace per year paid beyond that); refund entitlement is tenure-based (50% at 2 years paid, rising to a 90% cap); and a valid cancellation notice legally requires a full computed statement of account, not a bare status change. **[INFERENCE]** This is not Egyptian law, but it demonstrates that cancellation/refund logic is commonly *legally load-bearing*, not just an operational nicety — worth flagging to the brokerage as a "confirm local rules" item (see `10_Business_Questions.md`). [Respicio.ph]

**[DOCUMENTED — UAE precedent]** Dubai/UAE unit-swap practice under RERA-approved recovery schemes shows already-made payments can be **credited toward a new unit** rather than refunded in cash — but this is not standardized even within one jurisdiction; other developers demand fresh payment on a swap. Unit-transfer/swap handling is policy-configurable, not a fixed rule. [Gulf News]

---

## 10. Multi-branch operational friction

**[INFERENCE]** No source specifically documents multi-branch brokerage friction (inconsistent inventory views across offices, cross-branch lead-ownership conflicts). Recommend treating this as an open question for the brokerage rather than an established pain point (see `10_Business_Questions.md`).

---

## 11. Egyptian/MENA off-plan payment-plan norms (numeric findings)

These figures directly shape the payment-plan engine design in `04_Payment_Collection_Domain.md`.

**Down payments [DOCUMENTED, Egypt-specific, current market listings]:** observed range **0%–15%+** across current New Administrative Capital (NAC) projects — e.g., 0% (Zad Residence), 5% (Diplo East New Capital), 10% (The Island, Oro, Talah, ATIKA New Capital), 15% (Boca). [The Property; IPG Egypt; GPR Property ×2]

**Installment duration/frequency [DOCUMENTED, Egypt-specific]:** durations observed from **6 to 16 years** (6-yr Boca, 7-yr Oro, 8-yr The Island / ATIKA, 10-yr Talah "general market standard," 12–16-yr at some newer compounds). A separate source frames illustrative examples at 48 and 60 months, explicitly project-dependent. Frequency is typically **monthly** ("smoother for cash flow") or **quarterly** ("less administrative effort"). [The Property; csc-internationalproperty.com]

**Structure taxonomy [DOCUMENTED]:** three recurring patterns — (1) fixed periodic installments, (2) milestone-based payments tied to construction stages (reservation → contract → structural completion → finishing → handover), (3) hybrid (periodic installments + a larger lump sum near delivery). [csc-internationalproperty.com]

**Maintenance/club fees [DOCUMENTED, Egypt-specific]:** compound service charges reported in the range of **EGP 5,000–20,000/year**; separately, some NAC listings mention maintenance deposits up to **10%** of unit price plus separate club/garage membership costs; fee timing (pre- vs. post-handover) is negotiable and developer-specific. [Egyptian Real Estate Platform Blog; The Property; csc-internationalproperty.com]

**Commission economics [DOCUMENTED, Egypt-specific, multiple corroborating sources]:**
- Resale transactions: ~2.5% per side (buyer + seller) is one cited benchmark; a second source gives **1.5%–2.5%** ("around 2%" commonly cited), 2–5% for foreign-buyer deals.
- **Primary/off-plan (developer) sales: developer pays the full commission, typically 2%–5% of unit price** — the buyer pays nothing extra either way.
- **Regulatory cap:** recent amendments reportedly cap broker commission at **up to 5%** of unit price on developer-sold projects.
- **Payment timing is tranche-based, not lump sum:** one detailed source states **50–70% of commission is paid 30–60 days after the client's down payment**, and the remaining **30–50% is distributed in installments aligned with the client's ongoing payments, spread over 6–18 months**. Full upfront commission payment is described as rare. Brokerages must therefore carry cash-flow float between tranches — a direct requirement for the commission model (see `04`).
- Commission may additionally be delayed depending on developer cash flow — receipt is contingent and staggered, not contractually guaranteed on schedule.
[Leads-Estate; csc-internationalproperty.com; Egyptian Real Estate Platform Blog; Nawy; INVEST-GATE]

**Who collects the money [DOCUMENTED]:** on primary/off-plan sales, **the developer collects directly from the buyer**; the brokerage's commission is paid separately by the developer and does not flow through customer payments. [Nawy; Leads-Estate] **[NEWS-SOURCE corroboration]** buyer accounts in the Al Manassa investigation describe paying installments directly to the developer with no broker in the collection flow. **[INFERENCE, important for scope]:** this means the brokerage's system most plausibly needs to track *expected vs. reported-actual* customer payments for visibility and commission-calculation purposes — a "shadow ledger" reconciled against developer-reported collection status — rather than a full payment-processing/collection system that itself takes custody of customer money. This is the single most important open question for `05_Recommended_Business_Model.md` and is posed directly in `10_Business_Questions.md` (Q1–Q3).

**Post-dated cheques (PDCs) [DOCUMENTED for UAE, INFERENCE for Egypt]:** well-documented in Dubai — developers commonly request PDCs covering the full payment cycle from purchase through completion, a practice that emerged after 2009–2010 buyer defaults; bounced cheques carry serious civil/criminal-adjacent liability in the UAE. **No Egypt-specific source was found** confirming PDC usage in Egyptian real-estate installments, despite deferred/post-dated cheques ("شيكات مؤجلة") being a well-known general commercial payment instrument in Egypt. **Recommendation:** treat "Egyptian developers commonly collect via PDC" as **[INFERENCE]** until confirmed directly with the brokerage (`10_Business_Questions.md`, Q19) — do not present it as settled fact in customer-facing materials. [Gulf News]

---

## 12. AR/collections domain concepts (generalize to real estate)

**[DOCUMENTED]** Standard definitions: **Outstanding balance** = amount charged but not yet paid; it "doesn't mean overdue" — an invoice can be outstanding and still on time. **Overdue** = the portion of the outstanding balance whose due date has passed. [Stripe; NetSuite]

**[DOCUMENTED]** Standard AR aging buckets: **Current, 1–30, 31–60, 61–90, 90+ days past due.** [NetSuite] Construction/real-estate-specific adaptation uses similar bands (0–30, 31–60, 61–90 "red flag territory," 91–120, 120+ "critical, reduced recovery") and notes the **industry-average collection cycle in construction/real estate runs 60–90 days** — longer than many other sectors — with retention/holdback amounts tracked as a separate line item from standard receivables, and every receivable tagged to a specific project. [Projul]

**Relevance:** these are not real-estate-specific inventions — they are the standard AR vocabulary this project should reuse rather than re-invent, adapted with two domain-specific tweaks: (1) tie every installment to a specific unit/project, (2) treat delivery-linked final payments as a distinct risk category from routine periodic installments.

---

## Known research gaps (do not treat as settled)

1. WhatsApp-specific lead-leakage failure modes — no primary source found.
2. Post-dated cheques specifically in Egypt (vs. well-documented UAE) — no direct source found.
3. Multi-branch brokerage friction — no direct source found.
4. Unit double-booking/overselling — only inferred from the existence of preventive tooling, not a documented incident.
5. Exact delivery-payment percentage conventions in Egypt (e.g., "5% on delivery") — structural pattern confirmed, no universal number found.

---

## Source list

- PropSpace — Spreadsheets vs a CRM for Real Estate Brokerages: https://www.propspace.com/blog/spreadsheets-vs-crm-real-estate
- Ascendix — Compare Excel vs CRE CRM: https://ascendix.com/blog/cre-crm-vs-excel
- TryRealy — CRM Data Hygiene for Real Estate: https://tryrealy.com/blog/crm-data-hygiene-for-real-estate-fuzzy-match-dedupe-rules-required
- KeeTechnology — Real Estate CRM Mistakes: https://keetechnology.com/blog/real-estate-crm-mistakes
- MRI Software — 4 Cash Management Challenges in Real Estate Companies: https://www.mrisoftware.com/blog/4-cash-management-challenges-in-real-estate-companies-and-how-to-fix-them/
- Real Estate Business Review — Solutions for the Challenges of Commercial Real Estate Brokerage Operations: https://www.realestatebusinessreview.com/news/solutions-for-the-challenges-of-commercial-real-estate-brokerage-operations--nwid-1068.html
- Retyn.ai — Best Real Estate Commission Tracking Software: https://www.retyn.ai/blog/best-real-estate-commission-tracking-software-usa-accuracy-transparency
- Colibri Real Estate — Commission Disputes: https://www.colibrirealestate.com/career-hub/blog/real-estate-commission-dispute/
- Mashian Law Group — Agent Can Sue Agent for Commission-Sharing Disputes: https://mashianlaw.com/agent-can-sue-agent-for-commission-sharing-disputes/
- Hyperleap — Real Estate Lead Response Statistics 2026: https://hyperleap.ai/blog/real-estate-lead-response-statistics-2026
- Zoho/Pyramidbits — Real Estate CRM Unit Inventory: https://pyramidbits.tech/real-estate-crm-unit-inventory-with-zoho-crm/
- Builderopedia — Real Estate CRM with Inventory at a Glance Dashboard: https://builderopedia.com/real-estate-crm-inventory-dashboard/
- INVEST-GATE — Egypt's Real Estate Between Fragmented, Consolidated Brokers: https://invest-gate.me/features/egypts-real-estate-between-fragmented-consolidated-brokers/
- Property Finder EG — Egypt's New Real Estate Brokerage Law: https://www.propertyfinder.eg/blog/en/real-estate-brokerage-law-egypt/
- Zawia3 — Chaos in Egypt's Real Estate Market: https://zawia3.com/en/real-estate/
- Al Manassa — Broken promises, stalled homes: Egypt's real estate crisis leaves buyers in limbo: https://manassa.news/en/news/33412
- Andersen Egypt — Real Estate Installment Plans in Egypt: Managing Missed Payments: https://eg.andersen.com/real-estate-installment-plans/
- Egyptian Real Estate Platform Blog — Sell for Developers, Earn Like a Pro: https://blogs.realestate.gov.eg/sell-for-developers-earn-like-a-pro-guide-for-egyptian-brokers/
- Egyptian Real Estate Platform Blog — 10 Hidden Costs When Buying a House in Egypt: https://blogs.realestate.gov.eg/10-hidden-costs-when-buying-a-house-in-egypt/
- Nawy — Real Estate Developers, Brokers, and Nawy: https://www.nawy.com/blog/2676-real-estate-developers-brokers-and-cooing
- The Property — New Administrative Capital Egypt Offers: https://thepropertyeg.com/en/new-administrative-capital-egypt-offers/
- IPG Egypt — ATIKA New Capital: https://ipgegypt.com/en/projects/72-0-down-payment-10-years-installment-atika-new-capital-new-plan
- GPR Property — Zad Residence: https://gprproperty.com/en/project/zad-new-capital/
- GPR Property — Diplo East New Capital: https://gprproperty.com/en/project/diplo-east-new-capital/
- csc-internationalproperty.com — Property Payment Plans Egypt: https://csc-internationalproperty.com/property-payment-plans-egypt/
- csc-internationalproperty.com — Egypt Real Estate Broker Commission Fees: https://csc-internationalproperty.com/egypt-real-estate-broker-commission-fees/
- elbayt.com — How Payment Plans Are Making Property Ownership Easier in Egypt: https://elbayt.com/en/real-estate/how-payment-plans-are-making-property-ownership-easier-in-egypt
- Leads-Estate — Real Estate Broker Commission in Egypt 2026: https://leads-estate.com/en/blog/real-estate-broker-commission-egypt-guide
- Gulf News — Developers hedge bets with post-dated cheques: https://gulfnews.com/business/property/developers-hedge-bets-with-post-dated-cheques-1.1348807
- Gulf News — Recovery schemes: A silver lining: https://gulfnews.com/business/property/recovery-schemes-a-silver-lining-1.864559
- Respicio & Co. — Real Estate Developer Cancellation Notice Without Refund Plan (Maceda Law): https://www.respicio.ph/commentaries/real-estate-developer-cancellation-notice-without-refund-plan
- NetSuite — Accounts Receivable Aging Defined: https://www.netsuite.com/portal/resource/articles/accounting/accounts-receivable-aging.shtml
- Stripe — Outstanding balances: https://stripe.com/resources/more/outstanding-balances
- Projul — Construction Accounts Receivable Aging Report Guide: https://projul.com/blog/construction-accounts-receivable-aging-report-guide/
