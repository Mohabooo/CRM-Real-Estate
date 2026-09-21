# 27 — Workflows by Commercial Model

**Status:** Authoritative. Created in the September 2026 product-direction revision.

**Subject:** Side-by-side end-to-end workflows for the two commercial models, showing exactly what is shared and what differs. Read `25_Commercial_Models.md` first for why the models exist; this document is the operational detail.

Throughout: **OWN** = `own_inventory` (a brokerage or developer selling its own stock), **BRK** = `brokered_inventory` (a brokerage selling a developer's stock).

---

## 1. The shared spine

Both models run the identical operational sequence. This is the product.

```
Lead → Qualification → Project/Unit selection → Offer
   → Reservation (hold)
   → Deal (confirmed transaction)
   → Payment Plan selected/customised
   → Installment Schedule generated
   → [ MODEL-SPECIFIC FINANCIAL BEHAVIOUR ]
   → Commission entitlements
   → Management reporting
```

Everything before the bracketed step is byte-identical code and identical user experience. An agent selling a developer's unit and an agent selling the tenant's own unit do exactly the same job in the system up to the point where money changes hands.

---

## 2. Side-by-side walkthrough

| Step | OWN — tenant owns the stock | BRK — developer owns the stock |
|---|---|---|
| **Project setup** | Create project, `commercial_model = own_inventory`, no developer record | Create developer, then project with `commercial_model = brokered_inventory` and `developer_id` |
| **Commission setup** | Outbound rules only (agent, optionally TL/referrer), basis typically `net_value` | Inbound rule on the developer, plus outbound rules, basis typically `inbound_commission` |
| **Unit load** | Identical — CSV or manual | Identical |
| **Lead → qualification** | Identical | Identical |
| **Reservation** | Deposit is tenant cash if collected | Deposit is a *recorded confirmation* that the developer received it |
| **Deal creation** | Identical — price, discount, approval threshold | Identical |
| **Payment plan** | Identical engine, identical templates | Identical engine, identical templates |
| **Schedule generation** | Identical; the schedule is **live and operational** | Identical; the schedule is **reference information** |
| **Customer pays** | Tenant receives money; Payment recorded and allocated | Customer pays the developer; **nothing recorded in MVP** |
| **Down payment** | Derived from the ledger — down-payment installment `paid` | Recorded as a **confirmation milestone** (date + user) |
| **Outstanding / Overdue / Aging** | Fully computed and displayed | **Not computed**; UI states collection is with the developer |
| **Cash forecast** | Customer installments due by period | **Not applicable** — replaced by commission forecast |
| **Deal completion** | Automatic when the schedule is fully paid | **Manual** — the tenant marks its part done |
| **Inbound commission** | None | Earned when deal `completed` **and** down payment confirmed; then claimed and settled |
| **Outbound commission** | On the deal value, timing per tenant policy | Typically a share of inbound, timing per tenant policy |
| **Cancellation** | Tenant decides; tenant computes and pays refund | Developer decides; tenant **records** the outcome |
| **Revenue recognised** | Deal value | Commission only |
| **CEO dashboard** | Collections + sales + inventory | Sales + inventory + **commission pipeline** |

---

## 3. Worked scenario — OWN

*A developer-tenant sells unit A-1204 from its own project.*

1. Agent qualifies a lead and reserves A-1204 for 7 days.
2. Deal created: list 3,000,000, 5% discount approved by the branch manager, net 2,850,000.
3. Plan "10% + 8 years quarterly, 5% on delivery" applied → 285,000 down, 142,500 delivery, 32 × ~75,703.
4. Deal activated → unit `sold`; 34 installments live; **outbound** commissions created: agent 1.5% of net = 42,750, team leader 0.3% = 8,550. No inbound.
5. Customer pays the 285,000 down payment → Payment recorded, allocated to the down-payment installment, which becomes `paid`. `down_payment_satisfied` is now true by ledger.
6. If the agent's rule triggers `on_down_payment`, the agent's commission moves to `accrued`.
7. Quarterly installments are paid, recorded and allocated. Outstanding falls, overdue is computed on anything late, the CEO sees real cash collection.
8. Deal `completed` automatically when the last installment is paid.

**The tenant's money question:** *how much cash will come in next quarter, and what is late?*

---

## 4. Worked scenario — BRK

*A brokerage sells unit B-0704 in a developer's project.*

1. Agent qualifies a lead and reserves B-0704 for 7 days; the deposit is noted as confirmed by the developer.
2. Deal created with identical pricing and discount mechanics; net 2,850,000.
3. The developer's plan template is applied → identical schedule generated, held as **reference**: it tells the agent and the customer what the developer will expect, and frames the commission timing.
4. Deal activated → unit `sold`; installments generated but will stay `scheduled`; **inbound** commission created first (3% of net = 85,500, developer as counterparty), then **outbound** from it: agent 50% of inbound = 42,750, team leader 10% = 8,550, referring partner brokerage 10% = 8,550. Tenant retains ≈ 25,650.
5. Customer pays the developer directly. The tenant records **one fact**: down payment confirmed on a date, by a named user.
6. Tenant marks the deal `completed` once its part of the sale is done.
7. Both conditions met → inbound commission `accrued`. Finance `claims` it from the developer, then records receipts as they arrive in tranches, moving it to `settled`.
8. Outbound commissions trigger per the tenant's configured policy — commonly `on_inbound_received`, so agents are paid from money actually received.

**The tenant's money question:** *how much commission will we earn and receive, and what have developers not yet paid us?*

---

## 5. Cancellation compared

The revision brief asks specifically which cancellation behaviour is core and which must be configurable. The answer is clear once the models are separated.

**Common core — identical in both models:**
- A cancellation record with reason, requester and `initiated_by`.
- Deal → `cancelled`.
- Remaining installments → `void`, with paid history preserved.
- Unit → `available`.
- Commission clawback on every affected entitlement, via offsetting adjustments.
- Full audit.
- Cancelled deals leave forward-looking aggregates but stay in historical actuals (R-CAN-4).

**Differs — OWN:**
- The tenant holds the customer's money, so it **computes and pays a refund**. The refund policy is configurable with no system default: `02`§9 documents two incompatible real-world approaches (tenure-tiered entitlement versus flat partial forfeiture) and no Egyptian standard was found.
- A grace/cure period may apply before cancellation is final.
- The tenant controls the decision and can reject the request.

**Differs — BRK:**
- The **developer decides**. The tenant is recording someone else's outcome, not adjudicating one.
- Refund computation is **not the tenant's to perform**; the refund amount is recorded if the tenant learns it, and left null if not.
- No grace period is managed by the tenant.
- Clawback is often the more consequential effect than the refund: commission already accrued or settled must be reversed, and where the agent was already paid, the tenant carries the loss.

**Design consequence:** cancellation is one workflow with a model-aware policy at two points (refund computation and grace handling), not two separate features. Everything else is shared.

---

## 6. Dashboards compared

| Panel | OWN | BRK |
|---|---|---|
| Active deals (count + value) | Yes | Yes |
| Units available / reserved / sold | Yes | Yes |
| Sales by project / agent | Yes | Yes |
| Expected collections this period | Yes — tenant cash | Hidden; the schedule is the developer's receivable |
| Collected this period | Yes | Not available |
| Outstanding / Overdue / Aging | Yes | Not applicable — stated as such, never shown as zero |
| Cash collection forecast | Yes | Replaced by commission forecast |
| Commission earned / accrued / settled | Outbound only (a cost) | Inbound and outbound (revenue and cost) |
| Commission receivable overdue from developers | Not applicable | **Yes — a primary CEO metric** |

**For a mixed-portfolio tenant** the two financial views appear side by side and are never summed into one figure: one is customer cash, the other is commission revenue (`25`§7).

---

## 7. What an implementer must decide per model

| Decision | OWN | BRK |
|---|---|---|
| Developer record needed | No | Yes |
| Inbound commission rule | n/a | Required — basis, rate, trigger, payment terms |
| Outbound basis | Usually `net_value` | Usually `inbound_commission` |
| Outbound trigger | Tenant cash-flow choice | Tenant cash-flow choice; `on_inbound_received` is the conservative option |
| Refund policy | Must configure before using P2 cancellation | Not applicable |
| Grace period | Optional | Not applicable |
| Deal completion definition | Automatic | Must define what "our part is done" means |

All of these are tenant configuration. None requires code (`15`, revised).

---

## 8. The point of the comparison

Roughly 80% of the system — leads, customers, inventory, reservations, deals, the entire payment-plan engine, documents, audit, permissions, most dashboards — is identical across the models. The differences concentrate in four places: whether payments are recorded, whether collection metrics apply, whether inbound commission exists, and who owns the cancellation decision.

That concentration is what makes a single configurable product viable rather than two products sharing a codebase. It also means the risk of the multi-model direction is contained: if the model switch is implemented cleanly at the eight policy points in `25`§6, the core engine never needs to know which model it is serving.
