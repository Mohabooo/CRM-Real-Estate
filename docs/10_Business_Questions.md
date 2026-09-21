> **SUPERSEDED IN PART — September 2026 product-direction revision.**
> Several questions here were answered by the confirmed product direction (both commercial
> models supported; developer reconciliation and legal receipts out of MVP) and several
> were reclassified from blockers to tenant configuration. See revision 2 of
> `15_Business_Decisions.md` for the current triage.

# 10 — Business Questions / Decisions Required

**Subject:** Questions that cannot safely be assumed and must be answered by the brokerage before (or during) implementation. Every question below notes *why it matters* and *what this discovery assumed in its absence*, so the brokerage can see the consequence of each possible answer. Nothing in `04`–`09` should be read as having silently resolved these.

---

## Ownership & money flow (the most consequential fork — see `04`§2, `05`§1)

**Q1. Does the brokerage own the projects/inventory it sells, or does it sell developer-owned inventory?**
Why it matters: determines whether `Developer` is a real external counterparty (this discovery's assumption) or whether the brokerage *is* effectively the developer, in which case the "not an ERP" scope decision in `05` would need revisiting.
Assumed in this discovery: the brokerage sells developer-owned inventory (per BRD §3.6 and the mission's own framing "sell inventory belonging to developers").

**Q2. Does the brokerage collect money directly from customers, or does the developer collect directly?**
Why it matters: this is the single fork that decides whether the CRM needs true payment-processing/receipt-issuance capability or a "shadow ledger" for visibility only (`04`§2). Directly changes N1/N4 in `07` and the D-interpretation risk in `05`.
Assumed in this discovery: developer collects directly, per Egyptian evidence in `02`§11 — but this is market-pattern evidence, not a confirmed fact about *this* brokerage.

**Q3. If the developer collects directly, how does the brokerage find out what's actually been collected — a developer portal export, a monthly statement, a phone call, nothing formal at all?**
Why it matters: determines whether "confirmed collected" in the CRM is a reliable, timely field or a best-effort estimate — which changes how much confidence the CEO dashboard's "Collected" number should carry, and whether a reconciliation/import feature is needed.
Assumed in this discovery: not assumed — flagged as an open operational-process question with no default.

## Payment plan ownership & flexibility

**Q4. Who owns/defines the payment plan — the developer, or can the brokerage create its own custom plans?**
Assumed: both — Payment Plan Templates are modeled as attachable to a Project/Unit (developer-sourced) but the brokerage can also define custom ones (`04`§3.3).

**Q5. Can the payment plan be customized per customer (beyond the discount mechanism already modeled)?**
Assumed: yes, via the CustomerPaymentPlan instance + Discount object (`04`§3.6, §3.10); full ad-hoc restructuring is modeled as P2 plan-versioning (`04`§7).

**Q6. Can a plan change after a deal is created (restructuring, not just a discount)?**
Assumed: yes, phased P2 via plan versioning (`04`§7, `07` B10) — not MVP.

## Deals, customers & units

**Q7. Can a deal have multiple customers (co-buyers)?**
Assumed: modeled as P2 (`07` C4) pending this answer; MVP assumes a single primary customer per deal.

**Q8. Can a customer have multiple units/deals simultaneously?**
Assumed: yes, no structural constraint prevents it in `08`'s model (Customer → many Deals) — flagged to confirm there's no business reason to restrict this (e.g., credit/exposure limits).

**Q9. What happens when a customer cancels — is there a standard grace period, and is refund percentage policy-driven or fixed?**
Why it matters: directly determines the Cancellation entity's computation logic (`04`§3.11, §7). Two documented precedents exist with very different shapes (Maceda Law's tenure-based tiering vs. Egypt's observed informal partial-forfeiture pattern, `02`§9) — the brokerage's actual policy (or the developers' contractual policies it must honor) is unknown.
Assumed: nothing — modeled as fully configurable, not defaulted to either precedent.

**Q10. What happens when a unit is transferred to a different customer or a customer is moved to a different unit — does payment history carry forward as credit?**
Assumed: modeled as Future, configurable, not required for MVP (`04`§3.12).

**Q11. How are refunds actually processed — does the brokerage issue them, or does the developer, and does the CRM need to track a refund payout, not just a refund computation?**
Assumed: not assumed — this is a downstream consequence of Q2/Q9 not yet modeled beyond "computed refund amount" (`04`§3.11).

## Commission

**Q12. How are commissions calculated — % of sale value, % of collected amount, fixed amount, or does it vary by developer/project?**
Assumed: % of unit price is the Egyptian market norm (`02`§11, 2–5%), modeled as the CommissionRule default, but confirmed to vary by developer (`08` CommissionRule is per-Developer, not global).

**Q13. When is commission earned by the brokerage (Leg 1) — on deal signing, on developer's receipt of the customer's down payment, or something else?**
Assumed: modeled as a configurable trigger (`04`§6 Leg 1 state machine: expected → accrued → invoiced → received), not defaulted to one answer; Egyptian evidence (`02`§11) suggests accrual is tied to the customer's down payment, not signing alone.

**Q14. When does the agent get paid (Leg 2) — on deal closing, or pro-rata as the brokerage itself receives commission tranches from the developer?**
Why it matters: this is the single biggest cash-flow-risk decision in the whole commission model (`04`§6) — paying agents upfront on a deal that later cancels, before the brokerage itself has been paid, is a real exposure given Egyptian commission arrives in tranches over 6–18 months.
Assumed: not assumed — flagged as the most important unresolved policy question in the entire commission design.

**Q15. Are there different agent/team-leader split rules, and do they vary by branch or by deal type?**
Assumed: modeled as configurable per-deal split reference (`08` AgentCommissionPayout), not a single global rule.

**Q16. Is commission ever paid partly in non-cash form (bonuses, gifts, trips — mentioned in `02`§11 for developer-to-brokerage incentives)?**
Assumed: out of scope for MVP tracking; flagged since it appeared in Egyptian-market research as a real practice.

## Branches, currency, tax

**Q17. Does the brokerage have multiple branches, and does management need branch-level collection/commission reporting?**
Assumed: BRD already models Branch (§4); this discovery did not add branch-level money reporting to MVP (`09`) — confirm if needed sooner than P2.

**Q18. Are multiple currencies relevant (e.g., USD-denominated pricing for some projects, common in parts of the Egyptian market for certain segments)?**
Assumed: EGP-only for MVP (`07` N9) — confirm this holds for all target projects/developers.

**Q19. Are post-dated cheques (PDCs) actually used by this brokerage's developers for installment collection in Egypt?**
Why it matters: this discovery found strong documentation for UAE PDC practice but could not confirm it for Egypt specifically (`02`§11) — building a PDC sub-ledger speculatively risks wasted effort or a wrong-shaped feature.
Assumed: not assumed — explicitly gated behind this confirmation before P2 build (`04`§3.9, `07` D8).

**Q20. Are taxes/VAT relevant to any part of the money model the brokerage needs tracked (e.g., VAT on commission invoices to developers)?**
Assumed: out of scope for MVP; `07` N-series treats this as a data-capture concern at most, not a computed-tax-return feature.

## Process & integration

**Q21. Is there integration with accounting/ERP software the brokerage (or its developers) already use?**
Assumed: none for MVP — `05`§4 explicitly recommends against building toward this; revisit only if a specific integration is requested.

**Q22. Who records actual collections in the system — a dedicated Finance User role (already in BRD §4), Operations, or agents themselves?**
Assumed: BRD's existing Finance User / Operations User roles (§4) are the natural owners of Payment recording (`07` N1); confirm this matches actual staffing at the brokerage.

**Q23. Does the system need bank or payment-gateway integration?**
Assumed: no, per `05`§4's scope boundary — unless Q2's answer reveals the brokerage directly processes meaningful payment volume.

**Q24. Are receipts issued by the brokerage to customers, and if so, do they need to be legally valid financial documents (sequential numbering, tax compliance)?**
Assumed: not assumed — downstream of Q2; if yes, this would push the system meaningfully toward interpretation D (`05`§1) and should be re-scoped explicitly, not absorbed silently into MVP.

---

## How to use this document

Recommended approach: walk through Q1–Q3 and Q13–Q14 with the brokerage first — these five questions have the largest design-shape consequences (they determine whether `05`'s interpretation-C recommendation holds, and whether the commission model's cash-flow-risk decision is safe). The remaining questions can be resolved iteratively during MVP build without blocking the start of implementation, since `04`–`08` were deliberately designed to degrade gracefully (configurable flags, phased entities) rather than assume a single answer.
