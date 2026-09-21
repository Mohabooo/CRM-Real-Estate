# 26 — Commission Rules and Splits

**Status:** Authoritative. Created in the September 2026 product-direction revision. Replaces the two-leg commission design in `04`, `08` and revision 1 of `16`–`19`.

**Subject:** The configurable commission engine. The design goal is explicitly **not** to choose a commission model — it is to support the range of models that exist, with deal-level overrides and historical snapshotting.

---

## 1. Why configurable, on the evidence

A targeted research pass (September 2026) looked specifically for the commission conventions this engine would otherwise hardcode. The most useful result was a negative one.

**What is documented:**
- On primary/off-plan sales in Egypt, the **developer pays the brokerage** — typically **2–5% of unit price**, with a reported regulatory cap of 5%, paid in **tranches** (roughly 50–70% some 30–60 days after the customer's down payment, remainder over 6–18 months). **[DOCUMENTED — multiple sources, see `02`§11]**
- **Egyptian developers commonly run in-house sales teams** selling their own stock directly, in parallel with using external brokers — *"real estate developers have their own sales teams with the sole focus of selling their units"* **[DOCUMENTED — Nawy]**, and hire salaried sales staff directly, e.g. a Maadi real-estate sales role advertised at 12,000 EGP/month base **[JOB POSTING]**.
- **Inter-brokerage commission sharing operates at industrial scale in Egypt.** Nawy Partners is used by **6,500+ brokerage companies** for inventory access and commission management **[DOCUMENTED — trade press, Dec 2025]**, and Nawy describes co-broking resale listings "through a vast network of outside brokers" **[DOCUMENTED — company]**.
- Egyptian real-estate sales roles are advertised as **base salary plus commission**, not pure commission — e.g. "Basic salary … plus attractive commission" (New Cairo postings), with ~1,682 standing "Property Consultant – Commission Based" vacancies on one Egyptian job board **[JOB POSTING / RECRUITMENT DATA]**.
- Nearest quantified regional proxy, **Dubai**: *"Different companies offer different splits, ranging from 50% to 70% to the agent depending on experience, performance, and company structure,"* with *"no fixed real estate agent salary in Dubai"*; where a basic salary is offered it comes with **lower commission percentages, strict KPIs, and payback or clawback clauses**. Splits are **tiered by seniority**, not flat. **[DOCUMENTED — regional brokerage trade source]**

**What is NOT documented, after deliberate search:**
- **No Egyptian source publishes the internal agent split** — not as a percentage of the brokerage's commission, nor as a percentage of unit price. Arabic and English searches, job boards, trade blogs and the government's own broker-facing blog all address *client-facing* rates only and are explicitly silent on internal distribution. **[INFERENCE — no source found]**
- **No MENA evidence for team-leader/sales-manager percentage overrides.** The org role exists in Egypt (Cairo "Sales Team Leader – Real Estate" postings), but whether the TL earns a percentage override on team production or a target-based bonus is unsourced; all override literature found was US-market. **[INFERENCE — no source found]**
- **No published referral/co-broking rates**, despite the practice being confirmed at scale.
- **A correction to earlier work:** `02`§7 cited a claim that brokers handle "70–80% of developer sales volume." The follow-up pass could not substantiate this. The nearest real statistic — "sales brokerage retained 68.15% of 2025 revenue" (Mordor) — describes the sales-versus-leasing revenue mix *within the brokerage market*, not brokers' share of developer sales. **Treat the 70–80% figure as unsupported and stop citing it.**

**Conclusion.** Three of four researched dimensions have no published rate data in Egypt. That absence is the argument: **there is no Egyptian convention to hardcode, because none has been written down.** What the evidence does show is variation — by seniority, by company, by whether a base salary is paid, and by whether the counterparty is an employee, another brokerage, or an individual referrer. The engine must express that range. Selecting a "best" split would be inventing a standard, not implementing one.

---

## 2. Core concepts

**Direction.** Every commission is either:
- **Inbound** — revenue the tenant earns from an external party. Exists only in `brokered_inventory`.
- **Outbound** — what the tenant owes a participant. Exists in both commercial models.

**Participant.** Outbound commissions name a participant and a role: `agent`, `team_leader`, `referrer`, `other`. The counterparty may be an internal `user` or an external `partner` (another brokerage or an individual referrer) — the co-broking evidence in §1 makes the external payee a required capability, not a future nicety.

**Rule vs. entitlement.** A `CommissionRule` is configuration. A `Commission` is the entitlement created on a specific deal from a resolved rule, carrying a **snapshot** of that rule.

---

## 3. The rule model

| Field | Values | Notes |
|---|---|---|
| `direction` | `inbound` \| `outbound` | |
| `scope` | `tenant` \| `developer` \| `project` \| `deal` | Resolution is most-specific-first |
| `scope_id` | — | Null for tenant scope |
| `participant_role` | `agent` \| `team_leader` \| `referrer` \| `other` | Outbound only |
| `basis` | see §4 | What the percentage applies to |
| `calc_type` | `percent` \| `fixed` \| `tiered` | |
| `value` | decimal | Percentage or fixed amount |
| `tiers` | JSON | For `tiered` — thresholds and rates (P2) |
| `trigger` | see §5 | When the entitlement is earned |
| `effective_from` / `effective_to` | dates | Rules are versioned by effectivity, never edited in place |

### Rule resolution
For a given deal, direction and participant role, the applicable rule is the most specific active rule as of the deal date:

```
deal-scoped  →  project-scoped  →  developer-scoped  →  tenant-scoped  →  none
```

If no rule resolves, behaviour depends on direction *(clarified at the implementation gate)*:
- **Inbound on a brokered deal → activation is blocked.** A brokered deal's entire commercial purpose is the commission; activating one with no inbound rule is a misconfiguration, and letting it through produces a deal nobody will ever be paid for.
- **Outbound → activation proceeds with a warning**, no entitlement created. A salaried team with no commission scheme is a legitimate arrangement.

---

## 4. Calculation bases

The basis is the single most consequential configuration choice, and it differs systematically between commercial models.

| Basis | Meaning | Typical use |
|---|---|---|
| `gross_value` | Unit price before discount | Where the counterparty pays on list price regardless of negotiation |
| `net_value` | Deal value after discount | Common for own-inventory internal commission |
| `inbound_commission` | The tenant's own earned commission on the deal | The natural basis for outbound commission in `brokered_inventory` — "the agent gets 50% of what we earn" |
| `collected_amount` | Money actually collected to date | Own-inventory only; ties payout to cash (P2 — requires pro-rata recomputation) |
| `peer_commission` | Another named participant's entitlement on the same deal | *(added at the implementation gate)* Expresses "the team leader gets 10% of what the agent earns" — a real and distinct arrangement from "10% of what the company earns" |
| `fixed` | A flat amount, basis ignored | Per-unit bonuses |

**On `peer_commission`.** A rule using this basis names the participant role it derives from (`basis_participant_role`). Without it, an override expressed as a share of the agent's earnings would have to be hand-converted into an equivalent percentage of inbound — arithmetic that breaks silently the moment the agent's own rate changes. Because it creates a dependency between entitlements, the engine computes entitlements in dependency order and rejects cycles; the full determinism rules are in `28_Technical_Architecture.md` §9.

**Why `inbound_commission` matters.** The Dubai evidence describes agent splits as a share of *the brokerage's commission* (50–70%), not of the unit price. Without this basis the engine could not express the most common brokered arrangement in the region, and users would be forced into misleading equivalent percentages. Example: on a 2,850,000 net deal with 3% inbound commission of 85,500, "agent gets 50%" means 42,750 — whereas 50% of `net_value` would be 1,425,000. The two are not interchangeable, and a system that silently picks one is dangerous.

**Discount interaction.** Whether a discount reduces the commission basis is a direct consequence of choosing `gross_value` vs `net_value`. On the canonical example (`17`§5), 3% of gross = 90,000 vs 3% of net = 85,500 — a 4,500 EGP difference per deal. This is a configuration value, not a system assumption.

---

## 5. Triggers — when commission is earned

| Trigger | Meaning | Applies to |
|---|---|---|
| `on_deal_activation` | Earned as soon as the deal goes live | Either direction |
| `on_deal_completion_and_down_payment` | Earned when the deal is completed **and** the required customer down payment has been received | **Default for inbound** — the confirmed business rule |
| `on_down_payment` | Earned on down payment alone | Either |
| `on_inbound_received` | Earned when the tenant actually receives its inbound commission | Outbound only — protects tenant cash flow |
| `pro_rata_on_collection` | Accrues proportionally as the customer pays | Own-inventory only (P2) |
| `manual` | A human advances the state | Either — the MVP fallback |

**The confirmed inbound default.** For `brokered_inventory`, inbound commission is earned when the deal is completed **and** the required customer down payment has been received. This is configurable but ships as the default. The down-payment fact is the confirmation milestone described in `25`§4, not a reconciliation feed — the tenant records it once, from the developer or the customer.

**The outbound timing choice is the tenant's cash-flow decision.** `on_deal_activation` pays agents before the tenant is paid — fine for own-inventory (the tenant holds the customer's money) and risky for brokered (inbound arrives in tranches over 6–18 months, per §1). `on_inbound_received` inverts that risk onto the agent. The engine supports both and takes no position; the trade-off is documented so whoever configures it is choosing knowingly rather than accidentally.

---

## 6. Splits and multiple participants

Each participant is a **separate `Commission` row**, never fields on a shared row. This is what makes the split arrangement extensible without migration:

```
Deal #1234  (brokered_inventory)
├── inbound   developer → tenant     3% of net_value          = 85,500
├── outbound  agent (U. Hassan)      50% of inbound_commission = 42,750
├── outbound  team_leader (U. Adel)  10% of inbound_commission =  8,550
└── outbound  referrer (Partner Co.) 10% of inbound_commission =  8,550
                                          tenant retains ≈ 25,650
```

```
Deal #5678  (own_inventory)
├── outbound  agent (U. Mona)        1.5% of net_value        = 42,750
└── outbound  team_leader (U. Adel)  0.3% of net_value        =  8,550
                                     (no inbound — tenant owns the stock)
```

**Over-allocation check.** Where inbound exists, the system **warns** if total outbound exceeds inbound on a deal; it does not block, because a tenant may knowingly run a loss-leader. Where no inbound exists (own-inventory), no such check applies — outbound is simply a cost against deal revenue.

**Team-leader overrides are supported but not assumed.** Given no MENA evidence for percentage overrides (§1), the engine supports them and ships **no default team-leader rule**. A tenant that uses them configures them; a tenant that pays managers by bonus or salary configures nothing and sees no such rows.

**Base-salary and clawback context.** Egyptian roles commonly pair a base salary with commission, and the regional evidence documents clawback clauses on base where targets are missed. Payroll and base salary are **out of scope** (`25`, `20` out-of-scope list) — the engine tracks commission entitlements only. Clawback of *commission* on cancellation is in scope (§8).

---

## 7. Deal-level overrides

Two supported mechanisms, both audited and both snapshotted:

1. **Deal-scoped rule** — create a `CommissionRule` with `scope = deal`. Preferred where the whole arrangement differs.
2. **Direct override on the entitlement** — an authorised user sets the rate or amount on the `Commission` record with a mandatory reason. Preferred for a one-off adjustment.

In both cases `rule_snapshot` records what was actually applied, so the override is reconstructable years later.

---

## 8. Lifecycle, snapshotting and clawback

**States** (detail in `18`§8): `expected → accrued → claimed*/payable* → settled`, plus `clawed_back`. `claimed` applies to inbound (the tenant has billed the developer); `payable` applies to outbound (approved for payout). `overdue` is derived for inbound past its due date.

**Snapshotting is mandatory.** At deal activation, the resolved rule's full definition is copied into `rule_snapshot`. Changing or expiring a rule afterwards has no effect on existing commissions. This is what makes commission figures defensible in a dispute — and commission disputes are documented as a leading cause of agent churn and litigation (`02`§7).

**Clawback on cancellation.** Cancelling a deal moves affected commissions to `clawed_back` via an offsetting `CommissionAdjustment`, never by editing the original. Whether clawback is automatic or requires approval is configurable per tenant. In `brokered_inventory` a cancellation typically claws back both inbound and outbound; in `own_inventory` only outbound exists to claw back.

---

## 9. What is MVP and what is not

**MVP:** rule CRUD with all four scopes; `percent` and `fixed`; bases `gross_value`, `net_value`, `inbound_commission`; triggers `on_deal_activation`, `on_deal_completion_and_down_payment`, `on_inbound_received`, `manual`; unlimited outbound participants with internal or external payees; deal-level overrides; snapshotting; manual state transitions; clawback recorded manually; commission dashboards by state.

**P2:** `tiered` calculation; `pro_rata_on_collection`; automated trigger evaluation on collection and inbound-receipt events; inbound tranche schedules (modelling the 50–70% / 30–50% split explicitly rather than as one expected amount); automated clawback; commission statements per agent.

**Not planned:** payroll, base salary, base-salary clawback, tax on commission, commission payment execution.

---

## 10. Configuration checklist for a new tenant

What an implementer sets up, and what it means — none of it requiring code:

1. Default commercial model for new projects.
2. For each developer (brokered only): inbound rule — basis, rate, trigger, expected payment terms.
3. Outbound rule per participant role: basis, rate, trigger.
4. Whether team-leader and referrer rules exist at all.
5. Discount-affects-commission decision (implicit in choosing `gross_value` vs `net_value`).
6. Clawback automatic or approval-gated.
7. Approval thresholds for deal-level overrides.

Every item is a tenant configuration value, not a code change and not a blocker to development (`15`, revised).
