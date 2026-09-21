# 25 — Commercial Models

**Status:** Authoritative. Created in the September 2026 product-direction revision. Where earlier documents (`05`, `06`, `08`, `14`) assume a brokerage-only product, **this document supersedes them.**

**Subject:** The product is not a brokerage CRM. It is a **sales + inventory + payment-plan + collections platform** whose financial behaviour is determined by a configurable commercial model per project. This document defines those models and exactly what changes between them.

---

## 1. The correction being made

The discovery phase (`01`–`14`) concluded that the product was "a brokerage CRM + collections visibility layer" for a brokerage selling developer-owned inventory. That conclusion was drawn from evidence about *one* business scenario and was too narrow. The confirmed direction is broader: the same operational engine must serve a company that **owns** the inventory it sells and a company that **brokers** someone else's, and those two cases differ financially, not operationally.

This revision does not discard the discovery work — the payment-plan engine, the three-layer money model, the state machines and the integrity constraints all survive unchanged. What changes is that the **commercial behaviour is lifted out of the core and made configurable**, rather than being baked in as "the brokerage case."

---

## 2. Three business scenarios, two commercial models

| # | Business scenario | Who owns inventory | Who collects customer money | External commission | Internal commission |
|---|---|---|---|---|---|
| S1 | Brokerage that owns and sells its own stock | Tenant | Tenant | None | Yes |
| S2 | Brokerage selling a developer's inventory | External developer | Developer | Yes (developer → tenant) | Yes |
| S3 | Developer selling its own inventory direct | Tenant | Tenant | None | Yes |

**S1 and S3 are structurally identical.** Both are an owner selling its own stock, collecting its own money, paying its own salespeople, with no external commission counterparty. The only differences are vocabulary and org size — neither of which justifies a separate model in the data. Collapsing them is the single most important simplification in this revision, and it is exactly the kind of unnecessary abstraction the revised direction warns against.

**Therefore the product supports two commercial models:**

- **`own_inventory`** — the tenant owns the units, collects customer payments, and pays only internal commission. Serves S1 and S3.
- **`brokered_inventory`** — an external developer owns the units, the developer collects customer payments, and the tenant earns external commission which it partly distributes internally. Serves S2.

Evidence supporting S3 as a first-class case: Egyptian developers commonly run their own in-house sales teams selling directly to buyers, in parallel with using external brokerages — *"real estate developers have their own sales teams with the sole focus of selling their units"* **[DOCUMENTED — Nawy]**, and Egyptian developers advertise and hire their own salaried sales staff directly **[JOB POSTING — Wuzzuf/Jooble]**. Supporting a developer as a tenant is not speculative product expansion; it is an observed market segment.

---

## 3. Where the model lives: on the Project

**Decision:** `Project.commercial_model` is an enum field on the Project. Not a separate entity, not a polymorphic hierarchy, not a plugin.

**Why Project-level and not Tenant-level:** a real company may hold both kinds of stock simultaneously. A brokerage that mostly sells developer inventory may also own a small resale book; a developer selling its own projects may also broker a partner's. Tenant-level configuration would force such a company into two tenants, fragmenting its customers, agents and dashboards — the exact fragmentation this product exists to eliminate.

The Tenant carries a **default commercial model** used to pre-fill new projects, which keeps the common single-model case a one-time setting rather than a per-project decision.

**Consequence for the Developer entity — the requested architecture change:**

`Developer` is **no longer mandatory**. It is required only where the project actually belongs to an external developer:

```
Project.commercial_model = 'brokered_inventory'  →  developer_id REQUIRED
Project.commercial_model = 'own_inventory'       →  developer_id MUST BE NULL
```

Enforced by a database check constraint, not convention. For `own_inventory`, the owner is the tenant itself and creating a `Developer` row to represent the tenant would be a self-referential abstraction serving nothing.

**Model is fixed at project creation.** Changing a project's commercial model after deals exist would retroactively alter the meaning of its financial records. It is not an editable field; it requires an explicit, audited migration operation that is out of MVP scope.

---

## 4. What the commercial model determines

This table is the specification. Everything else in this revision follows from it.

| Behaviour | `own_inventory` | `brokered_inventory` |
|---|---|---|
| **Developer entity** | Not used (`developer_id` null) | Required |
| **Who collects customer money** | Tenant | External developer |
| **Payment ledger (actual money)** | Full — every payment recorded and allocated | Not tracked in MVP, with one exception (below) |
| **Expected installment schedule** | Generated, live, drives everything | Generated, **reference only** — informs the customer conversation and commission timing |
| **Outstanding / Overdue / Aging** | Fully computed | **Not applicable in MVP** — the tenant does not know what was actually collected |
| **Cash collection forecast** | Yes — forecast of money into the tenant | **No** — replaced by commission forecast |
| **Commission forecast** | Not applicable (no external revenue) | Yes — forecast of commission receipts |
| **External (inbound) commission** | None | Yes — developer owes the tenant |
| **Internal (outbound) commission** | Yes | Yes |
| **Internal commission basis** | Typically deal value | Typically the inbound commission (see `26`) |
| **Cancellation authority** | Tenant decides; tenant computes and pays refund | Developer decides; tenant **records the outcome** |
| **Refund computation** | Tenant policy, configurable | Out of tenant's control; recorded if known |
| **Revenue recognised by the tenant** | Deal value | Commission only |
| **CEO dashboard emphasis** | Collections + sales + inventory | Sales + inventory + commission pipeline |

### The one exception: down-payment confirmation on brokered deals

The confirmed rule is that external commission is earned when **the deal is completed AND the required customer down payment has been received.** That trigger needs a fact the tenant can know — which seems to contradict "developer collection is not tracked."

It does not, and the distinction matters:

- **Out of MVP:** ongoing reconciliation of every customer installment against the developer's records. That is a continuous data-feed problem the tenant cannot solve without developer cooperation (`15` — formerly D3).
- **In MVP:** a single **down-payment confirmation milestone** on the deal — a date and a confirming user. Brokerages always learn this fact, because it is the moment the sale becomes real and their own commission claim begins. It is one manually-recorded milestone, not a ledger.

So a brokered deal carries `down_payment_confirmed_at` and `down_payment_confirmed_by_user_id`, and the commission trigger reads that field. For `own_inventory`, the same logical fact is derived from the payment ledger (the down-payment installment is paid). `17` defines this as a single predicate that resolves differently per model, so nothing downstream needs to branch.

---

## 5. What does NOT change between models

The shared operational engine is the whole point of the product, and it is identical in both models:

```
Leads → Customers → Projects → Units → Deals
      → Payment Plans → Installments → Payments → Collections → Dashboards
```

Specifically unchanged by commercial model:
- Lead capture, assignment, activities, SLAs, stale detection.
- Customer records and history.
- Project, phase and unit inventory, including the one-active-deal-per-unit constraint.
- Reservations.
- Deal creation, pricing, discounts, approval thresholds.
- **The payment plan engine in full** — templates, instances, generation, rounding, date arithmetic. A brokered deal's schedule is generated by exactly the same code as an owned deal's.
- The template/instance separation: `PaymentPlanTemplate → CustomerPaymentPlan → Installments → Payments → PaymentAllocations`, with templates never mutating an existing customer's schedule.
- Audit, permissions, tenancy, import/export.

This is what makes the product reusable rather than two products sharing a logo.

---

## 6. Design principle: policy at the edges, engine in the middle

The commercial model must not become a set of `if` statements sprinkled through the codebase. The design rule is:

> The **core engine** is model-agnostic. The **commercial model** is consulted at a small number of explicit decision points, each of which is a named policy.

The complete set of policy points is:

| Policy point | Question it answers |
|---|---|
| `InventoryOwnershipPolicy` | Is a developer required? Who is the seller of record? |
| `CollectionPolicy` | Does the tenant record actual payments for this deal? |
| `CollectionMetricsPolicy` | Do outstanding/overdue/forecast apply to this deal? |
| `DownPaymentSatisfactionPolicy` | How do we know the down payment happened? |
| `ExternalCommissionPolicy` | Does inbound commission exist, and on what terms? |
| `InternalCommissionPolicy` | Who is entitled, on what basis, triggered when? |
| `CancellationPolicy` | Who decides, what is refunded, what is clawed back? |
| `DashboardPolicy` | Which financial panels apply? |

Eight decision points, each resolvable from `Project.commercial_model` plus tenant configuration. `21_Solution_Architecture.md` specifies where these live in the module structure.

---

## 7. Mixed-portfolio behaviour

A tenant with both kinds of project sees:
- **Combined** pipeline, inventory and sales figures — these are model-agnostic.
- **Separated** financial figures. Collections apply only to owned projects; commission pipeline applies primarily to brokered projects. Presenting a single blended "expected money" number across both would be meaningless, because one is customer cash and the other is commission revenue.
- A clear model indicator on every project, unit and deal, so a user always knows which rules apply to the record they are looking at.

The CEO dashboard therefore shows collections and commission as **parallel financial views**, not as one merged total (`19` F12, revised).

---

## 8. Terminology note

"Brokerage" is no longer the right word for the tenant in general. This document set uses:
- **Tenant** — the company using the system, whatever its type.
- **Seller of record** — the tenant in `own_inventory`; the developer in `brokered_inventory`.
- **Developer** — an external counterparty, present only in `brokered_inventory`.

Earlier documents use "the brokerage" throughout. Read it as "the tenant" except where the text is specifically discussing the brokered scenario.
