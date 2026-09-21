# 03 — Competitive Analysis

**Subject:** Research into real, existing products across three tiers — Egypt/MENA, international real-estate-specific platforms, and general installment/AR servicing patterns — to ground the design of the payment-plan/collection/commission layer described in `04_Payment_Collection_Domain.md`. Tags: **[DOCUMENTED]** (official docs/help center/pricing page), **[MARKETING CLAIM]** (vendor landing-page copy, unverified), **[YOUR INFERENCE]** (synthesis, not stated by any source). Full source list at the end.

---

## How to read this document

No single reviewed product documents all six capabilities the brokerage asked for (multi-scheme payment plans + auto-generated installment schedules + expected/collected/outstanding/overdue tracking + PDC handling + commission calculation + management dashboards) with full technical transparency. Coverage is fragmented across products — this document maps who is strongest where, and is explicit about what is marketing versus documented fact.

---

## Tier 1 — Egypt / MENA

### Nawy (Egypt)
Egypt's largest proptech/off-plan marketplace ($52M Series A, May 2025) [DOCUMENTED — TechCrunch]. Primarily a **consumer marketplace + broker network**, not a published back-office CRM. **Nawy Partners** offers partner brokers inventory access, direct developer contact, a sales-funnel dashboard, and claims to "track and calculate your commissions in real time" [MARKETING CLAIM — no calculation logic disclosed]. **Nawy Now** is a buyer-facing financing product ("move now, pay later," up to 7 years), not a brokerage payment-plan engine. **Finding:** no evidence of payment-plan management, installment-generation, or collections/overdue tooling for partner brokers — Egypt's largest proptech player has not (publicly) built the layer this brokerage is asking for.

### Property Finder (UAE/Egypt) & Bayut/dubizzle (UAE)
Both run **myCRM Lite/Pro** and **Bayut Profolio** respectively — explicitly lightweight **listings + lead management** tools (lead recording, performance dashboards, SmartLeads™ matching, verification badges). **[DOCUMENTED gap]:** neither mentions payment plans, installments, collections, or commission anywhere in their product documentation. These are portal/lead tools, not financial/deal systems.

### Aqarmap (Egypt)
Consumer listings portal with an MLS-style broker-sharing layer ("Exchange," launched ~2016) [DOCUMENTED — MAGNiTT/Zawya]. No CRM/payment-plan/commission product found; treated as portal-only.

### Sell.Do (India-origin, widely used across MENA/Gulf developers)
**The most extensively documented payment/collections feature set of any product reviewed.**
- **Payment Schedules & Milestones module**: "configurable construction-linked payment schedules that drive demands, interest and collections automatically" [MARKETING CLAIM, mechanics undisclosed].
- **Cost Sheets** at Project→Unit level, supporting "Full Agreement" vs. "Outside Agreement" charges — maps to a Project→Unit→Cost-Sheet(Payment-Plan) hierarchy [DOCUMENTED feature name].
- **Collections/overdue**: "interest calculator on overdue," age-analysis/recovery/risk reports [DOCUMENTED feature names, internal logic undisclosed].
- **Reconciliation**: "98% receipts auto-reconciled" via Razorpay integration + AI bank-narration matching [MARKETING CLAIM, no methodology given].
- **Commission**: listed as a feature category ("Commission Management," "Revenue Management") only on the third-party Capterra review page — **not** mentioned on Sell.Do's own product pages, a discrepancy worth noting.
- **Caveat:** a Capterra reviewer (self-described director-level) wrote *"Many features that breakdown way too often and certain features are created only as a showcase, but in reality their functionality does not exist"* — a signal that some payment/collection claims may be aspirational rather than fully robust in production [single-source anecdote].
- Pricing starts at $34.99/user/month.

### LeadSquared Real Estate CRM
Documents **inventory/lead tracking only** ("track and monitor every project, listing, and property"). **No mention anywhere** of payment plans, installments, collections, or commission — a clear, confirmed gap versus Sell.Do.

### Zoho CRM for Real Estate
- **Commission Management extension** (official Zoho help article) is **the best-documented commission engine found in this entire research pass**: lives in the Deals module; three plan types (Product Units — triggered on delivery+closed-won; Deal Count; Deal Age); tiered rate thresholds; commission frequency fixed to monthly; calculation/reporting only, **no payout/disbursement processing**.
- A third-party consultant article (not official Zoho docs) recommends a **Project → Building → Phase → Unit** hierarchy with three unit states (available/reserved/sold) and time-bound, auto-expiring Reservations linked to Deal+Client — informative as a pattern, not as an authoritative schema.
- **Finding:** Zoho's commission model is deal-level/aggregate, not installment-linked, and there is no documented native installment-schedule generator (would require Zoho Books integration or custom scripting).

### Odoo (core accounting + community real-estate modules)
- **Core "Payment Terms and installment plans"** (official docs) is the cleanest documented AR-installment mechanism found: a Payment Terms record contains ordered Term Rules (amount/% + due-date offset); generates **one separate journal-entry line per due date**, enabling per-installment tracking and feeding an aged-receivable report natively. This is a finance/invoicing-level mechanism, not a real-estate-specific Project/Unit/Deal model.
- **`kx_realestate`** community module has been **unpublished from the Odoo Apps Store** — a data point on the low maintenance-longevity of dedicated Odoo real-estate add-ons.
- **`in_payment_plane`** ("Sales Payment Plan & Installments," still listed): adds `payment.plane` (plan config) and `sale.order.installment.line` models; auto-computes due dates/amounts on a Sale Order (monthly/quarterly/semi-annual, down payments, custom discounts); one-click invoice generation per installment. Relies on Odoo Accounting's native invoice-paid state rather than a purpose-built collections ledger.
- **Independent corroboration:** Azdan's UAE CRM comparison states Odoo requires **custom development** for construction-milestone-linked payment plans — confirming Odoo's real-estate/installment capability is not out-of-the-box.

---

## Tier 2 — International real-estate-specific platforms

### Category distinction — the most important finding in this tier
**US resale-agent CRMs (REsimpli, Follow Up Boss, kvCORE, Realvolve) show zero evidence of payment-plan/installment/collections features**, because the US resale transaction model (single payment at closing via title/escrow, third-party-financed) has no structural need for a multi-year internal installment ledger. **Yardi** (Deal Manager) is confirmed to be **commercial-leasing/landlord/investor-reporting software**, not developer primary-sales software. **This confirms "real-estate agent CRM" (US resale) and "developer/off-plan sales CRM" (MENA/APAC/India primary market) are fundamentally different product categories** — the brokerage should not benchmark itself against, or expect inspiration from, mainstream US real-estate CRM marketing.

### Off-plan/developer-sales platforms (the actually-comparable category)

**Covercy Prime** (UAE-focused): [MARKETING CLAIM, unusually specific] project-level dashboard (units, availability, active deals); **two schedule-generation modes** — milestone-linked and calendar/time-based; overdue tracking described with real specificity ("see exactly which payments are overdue, by how many days, and the outstanding amounts," filterable by status/stage/buyer); broker commission status "in real-time" (earned/pending/due, methodology undisclosed); payment-pipeline charts groupable by quarter/month/week/day. Inferred object model: Development→Unit→Deal→Payment Milestone/Installment→Buyer, parallel to Broker→Commission-per-Deal.

**Linz Technologies** (developer CRM): EOI (expression of interest) module, Sales Offer module, "Payment Receipt & Installment Management" with automatic schedules + reminders, separate "Sales Commission & Broker Commission" modules claiming automatic calculation. No overdue/aging/delinquency module documented (reminders only).

**Azdan / NetSuite for Property Sales** (UAE NetSuite implementation): milestone/installment payment-plan configuration tied to construction phases; **PDC (post-dated cheque) management as a first-class, explicitly named feature** — "PDC and Off-Plan Ready," automated maturity alerts. **This is the single clearest evidence in the whole research pass that PDC handling is a genuine, distinct MENA-market requirement**, essentially absent from every India-origin (Sell.Do) or US-origin (Salesforce/Yardi/REsimpli) product reviewed. AR posting/outstanding-balance reporting and automated collection reminders are also named, leveraging NetSuite's native GL rather than a bolt-on ledger. Commission is mentioned only in passing ("supported," no detail).

**Property-xRM** (Microsoft Dynamics 365-based): documented **Portfolio → Properties → Units** hierarchy with unit split/merge under contract; "generate payment schedules and track payments" stated as a capability with **no installment-generation mechanics disclosed**; strong broker-commission **workflow** (approvals, batch allocation, portal visibility) but **zero documentation of the actual commission calculation formula** — process, not computation.

**Qobrix** (Cyprus): **Finance Management module** — payment plans record plan type, amount due, outstanding balances, due dates, invoice status, full payment-history (amount/method/date received), overdue and current-balance monitoring. **Second-most granular documented "expected vs. collected vs. outstanding vs. overdue" feature set** found (after Sell.Do/Covercy Prime), though installment-schedule *auto-generation* is not explicitly documented (describes tracking/recording, not generating from a template). No commission-tracking documentation found.

**Standard MENA off-plan payment-scheme vocabulary** (from developer buyer-education content, e.g. Binghatti): **80/20, 60/40, 70/30** construction-milestone-linked plans; **"1% monthly"** plans (5–15% down + ~1%/month for 2–5 years, sometimes extending post-handover); **Post-Handover Plans** (20–40% down, balance after possession); less-common pure time-based schedules. Typical horizon 2–6 years residential, longer for luxury. **This vocabulary (percentage-pair naming like "60/40") should be supported natively as a payment-plan template shorthand**, not just generic down-payment-%/installment-count fields.

---

## Tier 3 — General AR / installment-servicing architecture (generalizable pattern)

**[DOCUMENTED, cross-checked against Odoo's independent convergence on the same shape]** The recurring architecture across every serious installment-tracking system reviewed (loan servicing, Odoo, Sell.Do, Covercy Prime, Qobrix) is a **three-layer separation**:

1. **Schedule/Amortization layer (expected)** — a generated set of due dates + amounts, independent of actual payment; restructuring produces a *new* schedule version rather than editing history in place.
2. **Payment/Transaction layer (actual)** — each received payment posted as its own ledger entry with an allocation method (oldest-due-first, specific-installment, or custom) linking receipts back to specific expected installments; full audit trail.
3. **Delinquency/reconciliation layer (derived, computed continuously)** — a background comparison of layer 1 vs. layer 2 producing automated Days-Past-Due classification into aging buckets, not calculated ad hoc per report.

**Standard AR aging buckets** (industry-standard): Current, 1–30, 31–60, 61–90, 90+ days past due, tracked per customer/installment with original amount, remaining balance, due date, and reviewed as both $ and % of total receivables.

**Direct translation to this CRM [YOUR INFERENCE, grounded in the above]:**
- **Payment Plan** = the amortization template (type, frequency, down-payment %, duration, milestone rules).
- **Installment** = one generated line (unit/deal, due date, expected amount, status).
- **Payment/Receipt** = one actual payment record (amount, date, method — including PDC as a MENA-specific method with its own issued→deposited→cleared/bounced sub-state machine), allocated to installment(s).
- **Derived, not stored:** outstanding = expected − collected per installment; overdue = any installment past due date with outstanding > 0.
- **Commission**, gated on collection rather than deal closure, was **not explicitly documented by any vendor researched** — this is a genuine differentiation opportunity for the new CRM (see `04` and `05`), directly consistent with the Egyptian commission-tranche-payment evidence in `02`.

---

## Cross-cutting synthesis

1. **Best payment-plan/collections depth:** Sell.Do, Covercy Prime, Azdan/NetSuite (PDC specifically), Qobrix.
2. **Best documented commission engine:** Zoho's Commission Management extension — but it is deal-level, not collections-gated.
3. **Best unit/inventory object model:** Property-xRM, Zoho (via consultant article), Covercy Prime.
4. **Nobody documented a commission model gated on actual buyer collections** (vs. deal closure) — a likely whitespace, and directly aligned with the Egyptian evidence (`02`) that developer commission itself arrives in tranches tied to buyer payments.
5. **PDC handling is a genuine, distinct MENA requirement**, surfaced clearly only by Azdan/NetSuite among all products reviewed.
6. **Egypt-specific dedicated tooling is thin.** Nawy is the only major named Egyptian player with any commission-adjacent feature claim, and even that is a single unverified marketing sentence — most comparable tooling is UAE-centric. This supports the brokerage's underlying thesis that a purpose-built payment-plan/collections/commission CRM is a genuine gap in the Egyptian market specifically.
7. **Marketing-claim density is very high across the board.** Only Odoo's official docs, Zoho's official commission-extension help article, and general AR/loan-servicing architecture articles constitute genuinely [DOCUMENTED] technical detail; most vendor sites describe payment/collections/commission in one or two adjective-heavy sentences with no calculation logic disclosed. Treat every vendor "automatic," "real-time," or "AI-powered" claim in this document as marketing language unless explicitly tagged [DOCUMENTED].

## Comparison table

| Product | Category | Payment plan templates | Auto installment generation | Expected/collected/outstanding/overdue | PDC handling | Commission engine | Evidence quality |
|---|---|---|---|---|---|---|---|
| Nawy Partners | MENA broker network | Not documented | Not documented | Not documented | Not documented | Claimed, undisclosed | Marketing only |
| Property Finder / Bayut | Portal + lead CRM | None | None | None | None | None | Documented absence |
| Sell.Do | MENA/India developer CRM | [DOCUMENTED] name only | [MARKETING CLAIM] | [DOCUMENTED] named reports | Not documented | Listed on 3rd-party page only | Mixed, strongest overall |
| LeadSquared | Lead/inventory CRM | None | None | None | None | None | Documented absence |
| Zoho CRM | General CRM + extension | Not native | Not native | Not native | Not documented | **[DOCUMENTED]**, deal-level | Best documented commission |
| Odoo (core + modules) | ERP/accounting + add-ons | [DOCUMENTED] (finance-level) | [DOCUMENTED] (community module) | Via invoice status only | Not documented | Not native | Strong on finance mechanics, weak on RE-specific |
| Covercy Prime | UAE off-plan platform | [MARKETING CLAIM] specific | [MARKETING CLAIM] two modes | [MARKETING CLAIM] high specificity | Not documented | [MARKETING CLAIM] | Strong marketing specificity |
| Azdan/NetSuite | UAE developer ERP | [DOCUMENTED] milestone+installment | [DOCUMENTED] named | [DOCUMENTED] AR posting | **[DOCUMENTED]** — only one with this | Mentioned, no detail | Best for PDC |
| Qobrix | Cyprus/MENA CRM | [DOCUMENTED] recording | Not documented (tracking, not generation) | [DOCUMENTED] second-strongest | Not documented | None found | Strong on tracking |
| Property-xRM | Dynamics 365-based | Stated capability | Not disclosed | Not disclosed | Not documented | Workflow only, no formula | Strong workflow, weak computation |

---

## Full source list

1. https://www.nawy.com/blog/103946-nawy-partners-enhance-your-sales-get-higher-commissions
2. https://en.arageek.com/nawy-unveils-partners-platform-to-transform-egypts-real-estate-brokerage-scene
3. https://www.nawy.com/nawy-now
4. https://techcrunch.com/2025/05/11/egypts-nawy-lands-a-52m-series-a-to-take-on-mena/
5. https://www.propertyfinder.com/products/mycrm-lite-pro/
6. https://pfdn.propertyfinder.com/
7. https://www.bayut.com/mybayut/bayut-profolio-mobile-app/
8. https://magnitt.com/news/egypt%E2%80%99s-aqarmap-launches-exchange-32004
9. https://www.zawya.com/en/press-release/aqarmap-launches-exchange-egypts-real-estate-mls-rnes3oii
10. https://allcloud.io/case_studies/coldwell-banker-realty/
11. https://www.sell.do/real-estate-crm/online-payments
12. https://www.sell.do/real-estate-crm/expense-management
13. https://www.capterra.com/p/151826/Sell-Do/
14. https://www.leadsquared.com/real-estate-crm/
15. http://pages.leadsquared.com/real-estate-crm-book-my-demo
16. https://help.zoho.com/portal/en/kb/crm/extensions/sales/articles/commission-management-for-zoho-crm
17. https://pyramidbits.tech/real-estate-crm-unit-inventory-with-zoho-crm/
18. https://marketplace.zoho.com/app/verticals/crm/real-estate-developer-crm
19. https://www.odoo.com/documentation/17.0/applications/finance/accounting/customer_invoices/payment_terms.html
20. https://apps.odoo.com/apps/modules/18.0/in_payment_plane
21. https://apps.odoo.com/apps/modules/17.0/kx_realestate
22. https://www.dynexcel.com/blog/success-stories-2/sitara-developers-managing-plots-installments-and-accounts-for-an-entire-housing-scheme-on-odoo-16
23. https://www.yardi.com/product/deal-manager/
24. https://www.azdan.com/blog/top-crm-for-real-estate-developers-in-uae-2026
25. https://resimpli.com/features/
26. https://help.realvolve.com/hc/en-us/articles/206449003-Overview-of-Realvolve
27. https://www.covercy.com/prime/
28. https://www.covercy.com/
29. https://www.linztechnologies.com/real-estate-crm-for-developers
30. https://www.azdan.com/netsuite-for-property-sales/
31. https://propertyxrm.com/real-estate-broker-management/
32. https://propertyxrm.com/real-estate-inventory-management/
33. https://qobrix.com/real-estate-crm/finance-management/
34. https://www.zarinacrm.ae/crm-off-plan-property-sales-uae-guide/
35. https://www.delemontechnology.com/blog/off-plan-property-crm-software-dubai/
36. https://www.binghatti.com/en/blog/dubai-off-plan-payment-plans
37. https://lendfoundry.com/blog/loan-servicing-software-architecture-built-for-scale-compliance/
38. https://www.brex.com/spend-trends/accounting/accounts-receivable-aging-reports

**Follow-up recommended if needed:** Propertybase/Salesforce for Real Estate was only surface-reviewed (aggregator pages); a dedicated fetch of get.lwolf.com/propertybase/ is recommended before citing it specifically in customer-facing material.
