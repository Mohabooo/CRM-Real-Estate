# 19 — Product Requirements Document v1.1

**Revision 2 — September 2026.** Revised for the multi-commercial-model direction. Features are now model-aware where the commercial model changes behaviour, and the commission feature is rebuilt around the configurable engine. A new feature (F15, commission configuration) is added.

**Conventions**
- Rule references (`R-XXX-n`) → `17_Financial_Business_Rules.md`; states → `18`; entities → `16`; models → `25`; commission engine → `26`.
- **OWN** = `own_inventory`, **BRK** = `brokered_inventory`.
- Roles: `AG` Agent · `TL` Team Leader · `BM` Branch Manager · `OPS` Operations · `FIN` Finance · `OWN̲` Owner/CEO · `ADM` Platform Admin.
- Default visibility: Agent sees own; Team Leader sees team; Branch Manager sees branch; Owner sees all.
- **Model indicator requirement:** every project, unit, deal and financial screen displays which commercial model applies. A user must never have to infer which rules govern the record in front of them.

---

## F1 — Project and Developer Management

**Actor:** OPS, BM (manage); all (read).

**Main flow**
1. User creates a Project and chooses its **commercial model** (defaulted from the tenant setting).
2. If `brokered_inventory`, the user must select or create a **Developer**; if `own_inventory`, no developer is involved and the field is hidden.
3. User sets names (AR/EN), location, delivery date, phases.
4. User attaches PaymentPlanTemplates (F6) and confirms commission rules exist for this model (F15).
5. Project becomes `active`.

**Alternative flows**
- **A1** Bulk import of projects/units (F14).
- **A2** Developer created inline during brokered-project setup.
- **A3** Attempt to change a project's commercial model after deals exist → **rejected** (`25`§3); the field is read-only from first deal.

**Validation**
- `commercial_model = brokered_inventory` ⟺ `developer_id` present (DB-enforced).
- Delivery date required if any attached plan uses a delivery-linked payment.
- Project name unique per tenant (per developer where applicable).

**Permissions:** manage OPS+BM+OWN̲; read all in scope.
**Audit:** project create/edit, commercial-model selection, developer create/edit, rule attachment — **required**.

**Acceptance criteria**
- [ ] Creating an own-inventory project never asks for a developer and stores `developer_id` as null.
- [ ] Creating a brokered project without a developer is rejected.
- [ ] The commercial model becomes read-only once the project has its first deal.
- [ ] A tenant can hold both kinds of project simultaneously and both appear in one inventory list, each labelled.

---

## F2 — Unit Inventory *(model-agnostic, unchanged from v1.0)*

Create/import units, browse and filter, status lifecycle, block/unblock, status history. The one-active-reservation and one-active-deal invariants are DB-enforced and apply in **both** models.

**Acceptance criteria**
- [ ] Two simultaneous reservation attempts on one unit → exactly one succeeds, in either model.
- [ ] A unit cannot be sold twice.
- [ ] Unit price changes never alter existing deals.
- [ ] Every unit row shows its project's commercial model.

---

## F3 — Customer Management *(model-agnostic, unchanged from v1.0)*

Convert from lead or create directly; duplicate warning without auto-merge; consolidated view of deals, payment history and activity; co-buyers via `deal_customers` with a single primary in the MVP UI; national ID restricted and audited on access.

**Acceptance criteria**
- [ ] Duplicate phone raises a warning with a link and proceeds only on confirmation.
- [ ] A customer holding deals in both commercial models shows both, with financial sections presented separately.

---

## F4 — Reservations *(model-agnostic, one clarification)*

Request → confirm → convert/release/expire, with expiry reminders and manager extension within a limit.

**Model clarification:** a recorded deposit is tenant cash in OWN, and a *confirmation that the developer received it* in BRK. The field label reflects this.

**Acceptance criteria**
- [ ] Expired reservations release the unit and notify the agent.
- [ ] On conversion, an OWN deposit carries into the payment ledger without double-counting; a BRK deposit does not create a Payment record.

---

## F5 — Deal Creation

**Actor:** AG (draft); BM (activate, approve discounts).

**Main flow**
1. Agent creates a draft Deal; `commercial_model` is copied from the Project onto the Deal.
2. Gross value defaults to unit list price (R-VAL-1); discounts applied (F6) → net value.
3. Payment plan selected and schedule generated (F7).
4. BM activates → unit `sold`; installments live; **commissions created: inbound first if BRK, then outbound** (R-COMM-2, `18`§4).

**Alternative flows**
- **A1** From reservation — deal pre-populated, reservation → converted.
- **A2** Discount above threshold blocks activation pending approval.
- **A3** Unit taken concurrently → activation fails with a conflict message; draft preserved.
- **A4** No commission rule resolves → behaviour differs by direction (R-COMM-3): a **brokered** deal with no inbound rule is **blocked** from activation with an actionable message; a deal with no outbound rule **activates with a warning** and creates no entitlement.
- **A5 (BRK)** Recording the **down-payment confirmation** — a date and confirming user on the deal (R-DP-1). Available from activation onward.
- **A6 (BRK)** Marking the deal **completed** manually per the tenant's definition (`18`§4).

**Validation**
- Plan generated and R-PLAN-4 satisfied before activation.
- Unit not `sold` or `blocked`.
- BRK: down-payment confirmation requires a date not in the future.

**Permissions:** draft AG; activate BM (or AG under threshold); down-payment confirmation FIN+OPS; completion FIN+BM; cancel BM+OWN̲.
**Audit:** draft, discount, activation, down-payment confirmation, completion, cancellation — **required**.

**Acceptance criteria**
- [ ] A deal cannot activate without a valid schedule.
- [ ] Activation marks the unit sold in the same transaction.
- [ ] An OWN deal creates outbound commissions only; a BRK deal creates one inbound plus outbound.
- [ ] Outbound commissions based on `inbound_commission` compute correctly because inbound is created first.
- [ ] The down-payment confirmation field appears only on BRK deals.

---

## F6 — Payment Plans *(model-agnostic — identical engine in both models)*

Template definition with market shorthand labels; instantiation per deal; pre-generation adjustment; cash plans; archiving without affecting live instances; post-activation changes are P2 via versioning.

**Acceptance criteria**
- [ ] A template applied to a BRK deal generates the same schedule as the same template on an equivalent OWN deal.
- [ ] Editing a template leaves every existing instance byte-identical.
- [ ] A plan whose components don't sum to net value cannot be activated.

---

## F7 — Installment Generation

**Actor:** SYS.

**Main flow:** compute down payment, delivery payment and financed amount; base installment and remainder; dates with end-of-month clamping; preview; go live at deal activation. Identical logic in both models (R-PLAN, R-INST).

**Model behaviour**
- **OWN:** the schedule is the live obligation driving collections.
- **BRK:** the schedule is **reference information** — it tells the agent and customer what the developer expects and frames commission timing. Displayed with an explicit "collected by developer" label.

**Acceptance criteria**
- [ ] The `17`§5 worked example reproduces to the cent in both models.
- [ ] 31 January monthly start → 28/29 Feb → 31 Mar.
- [ ] A BRK schedule is visibly labelled as developer-collected and offers no "record payment" action.

---

## F8 — Payments — **OWN only**

**Actor:** FIN, OPS.
**Preconditions:** deal is `active` **and** `commercial_model = own_inventory`.

**Main flow:** record payment (amount, date, method, reference, proof) → `cleared` or `pending` → auto-allocation oldest-due-first → installment statuses and deal totals recompute.

**Alternative flows**
- **A1** Manual allocation to chosen installments.
- **A2** Partial payment → `partially_paid`, remainder still ages.
- **A3** Overpayment → future installments, then a deal credit balance.
- **A4** Bounce/void → allocations reverse, statuses restore, notification raised.
- **A5 (BRK)** Attempting to record a payment against a brokered deal is **rejected** with an explanation (R-PAY-0) — the action is not offered in the UI and the API refuses it.

**Validation:** amount > 0; not unreasonably future-dated; allocations ≤ payment amount; deal is OWN and not cancelled.
**Permissions:** record/clear FIN+OPS; void FIN **and** BM; agents cannot record payments.
**Audit:** every create, clear, bounce, void, allocation and reversal — **required**.

**Acceptance criteria**
- [ ] A payment covering 2.5 installments produces two `paid` and one `partially_paid`.
- [ ] Voiding it returns all three to their prior state and the deal totals to their prior values.
- [ ] A `pending` payment does not appear in Actual Collections.
- [ ] The API rejects a payment against a BRK deal with a clear reason.

---

## F9 — Collections Tracking — **OWN only**

**Actor:** FIN, OPS, BM, OWN̲ (view); AG (own deals).

**Main flow:** filter by period and scope; see **five distinct figures** — Expected, Actual, Outstanding (due), Total Remaining, Overdue; drill from any figure to underlying installments and payments; export CSV.

**Model behaviour**
- **OWN:** all five computed (R-EXP-1, R-ACT-1, R-OUT-1, R-OUT-2, R-OVD-2).
- **BRK:** collections views **exclude** brokered deals (R-SCOPE-2). Where a user opens a brokered deal's financial tab, the system states that collection is handled by the developer and shows the reference schedule — **it does not display zeros** (R-BRK-1), because a zero reads as "nothing owed."

**Validation:** every aggregate must be reproducible from its drill-down; a mismatch is a release blocker.
**Permissions:** OWN̲ all; BM branch; FIN/OPS all financial; AG own deals.
**Audit:** financial exports — **required**.

**Acceptance criteria**
- [ ] Outstanding and Total Remaining are labelled distinctly and differ on any deal with future installments.
- [ ] A March payment against a January installment appears in March Actual and clears January Overdue.
- [ ] A mixed-portfolio tenant's collections totals include only own-inventory deals.
- [ ] Opening a brokered deal's financial tab shows an explanatory state, not zeros.

---

## F10 — Overdue Management — **OWN only**

Overdue computed continuously (never a stored flag); queue with DPD and aging buckets; notifications at configured thresholds; follow-up logging; grace period support.

**Model behaviour:** brokered deals never appear in the overdue queue and generate no overdue notifications — the tenant has no basis to assert a customer is late.

**Acceptance criteria**
- [ ] With grace = 5, an installment 3 days past due is Outstanding but not Overdue.
- [ ] A customer with overdue amounts in two buckets appears in both with the correct split.
- [ ] No brokered deal ever appears in the overdue queue.
- [ ] Recording payment removes the amount from the queue immediately, with no job dependency.

---

## F11 — Commission Management *(substantially rebuilt)*

**Actor:** FIN (manage); BM/OWN̲ (view); AG (own entitlements).

**Main flow**
1. On deal activation, the system resolves rules most-specific-first and creates entitlements: **inbound first (BRK only), then outbound** (R-COMM-2/3).
2. Each entitlement stores a **snapshot** of the rule applied (R-COMM-4).
3. Finance advances states per `18`§8 — manual in MVP, automated triggers in P2.
4. Dashboards show Expected / Accrued / Claimed-or-Payable / Settled / Overdue / Clawed-back, separated by direction.

**Alternative flows**
- **A1** Multiple outbound participants — agent, team leader, referrer, other — each a separate row, internal user or external partner.
- **A2** Deal-level override — a deal-scoped rule or a direct rate override with mandatory reason, both snapshotted (`26`§7).
- **A3** Total outbound exceeds inbound → **warning**, not a block (R-COMM-8).
- **A4** No rule resolves → no entitlement, activation warning.
- **A5** Inbound trigger met — deal `completed` **and** down payment satisfied → `accrued` (R-COMM-6).
- **A6** Developer commission overdue — claimed and unsettled past due date → flagged for the CEO.
- **A7** Cancellation → clawback via offsetting adjustment, never by editing the original (R-COMM-9).

**Validation**
- An entitlement cannot exist without a snapshotted rule and rate.
- Inbound entitlements only on BRK deals (R-COMM-5).
- Outbound rules using `inbound_commission` as basis are invalid on OWN projects — caught at rule configuration, not at deal time.

**Permissions:** state changes FIN; payout approval FIN+BM; agents see only their own entitlements; BM branch; OWN̲ all.
**Audit:** creation, every state change, override, clawback — **required**.

**Acceptance criteria**
- [ ] Changing a commission rule does not alter any existing entitlement.
- [ ] An OWN deal produces no inbound entitlement.
- [ ] A BRK deal with a 50/10/10 split produces four rows summing correctly against a 3% inbound.
- [ ] An agent cannot see another agent's entitlements.
- [ ] Clawback leaves the original entitlement intact and adds an offsetting adjustment.

---

## F12 — CEO Dashboard *(model-aware)*

**Actor:** OWN̲ (primary); BM (branch-scoped, P2).

**Main flow:** headline strip, then sections; every figure drills through; every view exports.

**Headline strip — model-dependent**
- **Own-inventory portfolio:** Expected this month · Collected this month · Outstanding · Overdue (with buckets) · Active deals count+value · Unit status counts.
- **Brokered portfolio:** Active deals count+value · Unit status counts · Commission expected this period · Commission settled this period · Commission receivable outstanding · **Commission overdue from developers**.
- **Mixed portfolio:** both financial blocks shown **side by side, never summed** (`25`§7) — one is customer cash, the other commission revenue.

**Sections:** Sales (both) · Collections (OWN only) · Commission pipeline (both, inbound only where it exists) · Inventory (both) · Projects (both, labelled by model).

**Alternative flows**
- **A1** Period switch — month/quarter/year applies consistently.
- **A2** Cash forecast (OWN) and commission forecast (BRK) are distinct panels (R-FCT-1, R-FCT-4).
- **A3** Branch scope — P2.

**Validation:** no dashboard figure may use a different formula than its drill-down. Cash-collection figures exclude brokered deals (R-SCOPE-2).

**Acceptance criteria**
- [ ] Every headline figure matches its drill-down total.
- [ ] A pure-brokered tenant sees no empty collections panels — they are absent, not zeroed.
- [ ] A mixed tenant never sees customer cash and commission revenue added together.
- [ ] Expected and Collected remain visibly distinct when a payment arrives late.

---

## F13 — Notifications *(one model adjustment)*

Triggers: installment due soon and overdue (**OWN only**), payment bounced (OWN only), reservation expiring/expired (both), lead stale (both), **commission trigger met** and **commission receivable overdue** (both, P2 for automation).

**Acceptance criteria**
- [ ] No overdue notification is ever generated for a brokered deal.
- [ ] Paying before the threshold suppresses the notification.
- [ ] Notification failure never blocks the underlying transaction.

---

## F14 — Import / Export *(one descope)*

Template download, row-by-row validation with per-row errors, ImportJob retention, filtered CSV export of any list view, bulk unit price update that never touches existing deals.

**Descoped in this revision:** developer payment-reconciliation import. Developer-side collection reconciliation is out of MVP (`25`§4), so there is nothing to import. The generic importer ships regardless and can absorb this later if reconciliation is ever built.

**Permissions:** unit/lead/customer imports OPS+BM; payment imports FIN only (OWN deals only).
**Audit:** every import job and every financial export — **required**.

**Acceptance criteria**
- [ ] 3 invalid rows out of 100 → 97 imported, 3 reported with row numbers and reasons.
- [ ] A payment import cannot create payments against a brokered or cancelled deal.

---

## F15 — Commission Configuration *(new)*

**Actor:** OWN̲, FIN (manage); BM (read).

**Main flow**
1. User opens commission settings and sees rules grouped by direction and scope.
2. User creates a rule: direction, scope (tenant/developer/project/deal), participant role, basis, calculation type, value, trigger, effective dates.
3. System previews the effect on a sample deal value before saving.
4. Saved rules apply to **future** deal activations only.

**Alternative flows**
- **A1** Rule supersession — a new effective-dated rule rather than editing an existing one.
- **A2** Deal-level override created from a deal screen (`26`§7).
- **A3** Conflicting rules at the same scope and date → rejected with both shown.
- **A4** Configuration checklist for onboarding (`26`§10) guides a new tenant through the minimum rule set.

**Validation**
- `inbound` rules require a developer scope or a brokered project scope.
- `inbound_commission` basis is invalid for own-inventory scopes.
- Percentage values 0–100; fixed values > 0; effective ranges non-overlapping within the same scope, direction and role.

**Permissions:** manage OWN̲+FIN only; read BM.
**Audit:** every rule create, supersede, deactivate — **required**.

**Acceptance criteria**
- [ ] A tenant can configure at least: agent-only; agent+TL; agent+TL+referrer; and external-partner payee.
- [ ] A rule change never alters existing entitlements.
- [ ] The preview shows the computed amount for a sample deal before saving.
- [ ] Configuring an inbound rule on an own-inventory project is refused with an explanation.

---

## Cross-cutting requirements

**Model transparency.** Every screen showing financial data states which commercial model governs it. Absent capabilities are shown as explicit explanatory states, never as zeros or empty panels.

**Audit.** Every money-touching mutation records actor, timestamp, entity, before/after and reason where required. Immutable; readable by OWN̲ and ADM.

**Permissions.** Fixed roles with branch scoping; field-level restriction on national ID and commission figures. Custom RBAC remains P2/Future.

**Localization.** Arabic/English with RTL, locale-aware numerals and dates, EGP formatting, Africa/Cairo dates.

**Non-functional.** Per `07` N1–N12; financial integrity and auditability take precedence over performance where they conflict.
