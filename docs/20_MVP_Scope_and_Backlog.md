# 20 — MVP Scope and Backlog

**Revision 2 — September 2026.** Revised for the multi-commercial-model direction. Key changes: the scope boundary now covers both commercial models; the commission epic is **no longer blocked** (it builds a configurable engine, with values supplied at tenant configuration); a new epic covers commission configuration; several stories are now model-aware.

---

## Part A — Scope boundary

### MVP (build now)

**Core platform:** multi-tenancy, auth, fixed roles with branch scope, audit infrastructure, CSV import/export, documents, in-app + email notifications.

**CRM:** leads with stages and ownership, manual + bulk assignment, activities with next-action and stale queues, tasks, customers with duplicate warnings.

**Commercial models:** per-project commercial model (`own_inventory` | `brokered_inventory`); Developer entity required only for brokered projects; mixed portfolios in one tenant; model indicator throughout the UI.

**Inventory:** projects, phases, units with status lifecycle and history; CSV import; agent browse/filter; DB-enforced one-active-reservation and one-active-deal invariants.

**Reservations:** hold → confirm → convert/release/expire, deposit capture, expiry reminders.

**Deals and plans:** Deal object with denormalised commercial model; PaymentPlanTemplate with market-shorthand presets; CustomerPaymentPlan instance; automatic installment generation (identical in both models); Discount with approval threshold; down-payment confirmation milestone (brokered); manual deal completion (brokered).

**Collections (own-inventory):** payment recording with auto and manual allocation; clearing, bouncing and voiding with full reversal; computed Outstanding, Total Remaining, Overdue with configurable aging buckets; cash collection forecast.

**Commission (both models):** configurable rule engine — direction, four scopes, three calculation types, five bases, six triggers; unlimited participants with internal or external payees; deal-level overrides; mandatory rule snapshotting; manual state transitions; clawback recorded; commission configuration UI.

**Dashboards:** model-aware CEO dashboard — collections panels for own inventory, commission pipeline for brokered, side by side and never summed for mixed portfolios.

### P2 (fast-follow)

Payment plan versioning for mid-deal changes · Cancellation workflow with model-aware refund computation and automated clawback · Automated commission triggers on collection and inbound-receipt events · Tiered commission rules · Inbound commission tranche schedules · `pro_rata_on_collection` trigger · Risk-adjusted cash forecast · Collections by customer/agent/plan · PDC sub-ledger *(gated — unconfirmed for Egypt)* · Administrative/maintenance fee schedules · Per-agent commission statements · Branch-scoped financial reporting · Promise-to-pay tracking · PDF report polish.

### Future

Developer collection reconciliation (if ever) · Unit transfer with credit carry-forward · Balloon and step-up installments · Milestone/construction-linked schedules · Multi-currency · Developer inventory API sync · Native mobile apps · WhatsApp Business API beyond deep-link logging · Custom RBAC · BI warehouse.

### Explicitly out of scope (a decision, not a phase)

General ledger and double-entry bookkeeping · Accounts payable, payroll, base salary, base-salary clawback · Bank reconciliation engine · Payment gateway processing · Legally valid receipt generation · Tax computation and filing · Multi-entity consolidated financial statements · Developer-side construction/project management · Late-payment penalty and interest calculation.

**Two descopes confirmed in this revision:** legally valid receipt generation, and developer-side collection reconciliation. Both were previously open questions; both are now settled as out of MVP.

---

## Part B — Epics and user stories

Ten MVP epics. Stories marked **[OWN]** or **[BRK]** are model-specific; unmarked stories apply to both.

---

### EPIC 1 — Foundation: tenancy, identity, access, audit

**E1-S1** — As a platform admin, I can provision a tenant with a default commercial model so new projects are pre-configured.
- [ ] Creating a tenant creates an owner user, an initial branch, and a default commercial model setting.
- [ ] No query can return another tenant's rows, verified by an automated cross-tenant test.

**E1-S2** — As an owner, I can invite users with a role and branch so they see only what they should.
- [ ] Invited user sets a password and lands scoped to their branch.
- [ ] An agent cannot open another branch's lead, deal or customer.

**E1-S3** — As an owner, I can deactivate a user without losing their historical records.
- [ ] Deactivated user cannot log in; their leads, deals and commission entitlements remain intact and attributable.

**E1-S4** — As a compliance-minded owner, I can see an immutable audit trail of sensitive actions.
- [ ] Every action listed as audited in `18` produces a record with actor, timestamp, before/after.
- [ ] Audit records cannot be edited or deleted through any API.

---

### EPIC 2 — CRM core

**E2-S1** — As an agent, I can capture a lead with Egyptian phone formats and see a duplicate warning.
- [ ] Phone variants normalise to one canonical form; matches raise a warning with a link; no auto-merge.

**E2-S2** — As a manager, I can assign and bulk-reassign leads with a reason.
- [ ] Assignment audited; new owner notified; 50-lead bulk reassign is one auditable operation.

**E2-S3** — As an agent, I can log activities and set a next action.
- [ ] Types include call, meeting, WhatsApp summary, note; leads past next-action appear in a stale queue.

**E2-S4** — As an agent, I can convert a qualified lead into a customer.
- [ ] Contact details carry across, records link, lead leaves the funnel without deletion.

---

### EPIC 3 — Commercial models and inventory

**E3-S1** — As ops, I can create a project and choose its commercial model.
- [ ] Own-inventory projects never ask for a developer; `developer_id` is null.
- [ ] Brokered projects require a developer; creation without one is rejected.
- [ ] The model becomes read-only once the project has a deal.

**E3-S2** **[BRK]** — As ops, I can register an external developer with its commercial terms.
- [ ] Developer requires a name; an inbound commission rule is required before a deal on its inventory can activate.

**E3-S3** — As ops, I can create projects and units individually and by CSV.
- [ ] CSV import reports per-row errors without aborting valid rows; unit code unique within project.

**E3-S4** — As an agent, I can browse and filter available inventory across both models in one list.
- [ ] Filters: project, type, price range, area, status; each row shows its commercial model.
- [ ] Sold and blocked units excluded from the default view.

**E3-S5** — As the system, I prevent the same unit being sold twice, in either model.
- [ ] Concurrent reservation attempts: exactly one succeeds.
- [ ] Activating a deal on a sold unit fails with a clear conflict error.
- [ ] Enforced at database level, verified by a concurrency test.

---

### EPIC 4 — Reservations

**E4-S1** — As an agent, I can reserve an available unit with an expiry.
- [ ] Unit becomes `reserved` only on confirmation; expiry defaults from tenant settings.

**E4-S2** — As the system, I expire stale reservations and free the unit.
- [ ] Expiry job releases the unit and notifies agent and manager; an expired reservation cannot convert.

**E4-S3** — As a branch manager, I can extend a reservation within a limit.
- [ ] Beyond the limit requires owner approval; every extension audited.

**E4-S4** — As an agent, I can record a deposit appropriate to the commercial model.
- [ ] **[OWN]** deposit carries into the payment ledger on conversion without double-counting.
- [ ] **[BRK]** deposit is recorded as a developer-received confirmation and creates no Payment record.

---

### EPIC 5 — Deals and payment plans

**E5-S1** — As an agent, I can create a draft deal from a unit and customer.
- [ ] Gross value defaults to list price and freezes on the deal; later unit price changes don't affect it.
- [ ] The deal carries its project's commercial model.

**E5-S2** — As an agent, I can apply a discount and see net value update.
- [ ] Percentage and fixed both supported, additive against gross; above-threshold discounts block activation pending approval; total discount cannot exceed gross.

**E5-S3** — As ops, I can define reusable payment plan templates with market shorthand labels.
- [ ] A template can express "10% down, 8 years quarterly, 5% on delivery"; archiving leaves live deals unaffected.

**E5-S4** — As an agent, I can apply a template and preview the generated schedule, in either model.
- [ ] Down payment, installments and delivery tranche sum exactly to net value.
- [ ] The `17`§5 worked example reproduces to the cent.
- [ ] 31 Jan monthly start → 28/29 Feb → 31 Mar.
- [ ] The same template on equivalent OWN and BRK deals produces identical schedules.

**E5-S5** — As a manager, I can activate a deal, locking inventory and creating entitlements.
- [ ] Activation refused without a valid schedule; unit → sold in the same transaction.
- [ ] **[OWN]** outbound entitlements only.
- [ ] **[BRK]** one inbound plus outbound, inbound computed first so `inbound_commission` bases resolve.

**E5-S6** — As an agent, I cannot alter a schedule after money has been allocated.
- [ ] Edit attempts after first allocation are refused with an explanation.

**E5-S7** **[BRK]** — As finance, I can record that the customer's down payment was received by the developer.
- [ ] A date and confirming user are captured; no Payment record is created.
- [ ] The field appears only on brokered deals.
- [ ] Recording it may satisfy the inbound commission trigger.

**E5-S8** **[BRK]** — As finance, I can mark a brokered deal completed.
- [ ] Completion is manual and audited; combined with down-payment confirmation it accrues inbound commission.

---

### EPIC 6 — Payments and collections **[OWN]**

**E6-S1** — As finance, I can record a payment against an own-inventory deal with proof.
- [ ] Amount, date, method, reference captured; document attachable; pending payments don't count as collected.

**E6-S2** — As the system, I allocate payments oldest-due-first.
- [ ] A payment covering 2.5 installments produces two `paid` and one `partially_paid`; allocation never exceeds the payment.

**E6-S3** — As finance, I can manually allocate a payment to chosen installments.
- [ ] Manual allocation overrides the default order and is audited.

**E6-S4** — As finance, I can void or bounce a payment and have everything unwind correctly.
- [ ] All allocations reverse; statuses and totals return to prior values; history preserved by reversal rows; voiding requires finance plus manager.

**E6-S5** — As finance, I see expected, collected, outstanding, total remaining and overdue as five distinct figures.
- [ ] Each matches its drill-down total; Outstanding and Total Remaining differ on any deal with future installments.

**E6-S6** — As the system, I refuse payment recording against brokered deals.
- [ ] The action is absent from the UI and the API rejects it with a clear reason.
- [ ] Collections aggregates exclude brokered deals entirely.

**E6-S7** — As an agent, I can see payment status of my own deals only.

---

### EPIC 7 — Overdue management **[OWN]**

**E7-S1** — As finance, I see an overdue queue with DPD and aging buckets.
- [ ] Buckets mutually exclusive and exhaustive, boundaries configurable; partially-paid installments contribute only their unpaid portion.

**E7-S2** — As an agent, I am notified when my customer's installment becomes overdue.
- [ ] Exactly one notification per installment per threshold; paying beforehand suppresses it.

**E7-S3** — As a tenant admin, I can set a grace period before an installment counts as overdue.
- [ ] With grace = 5, an installment 3 days past due is outstanding but not overdue.

**E7-S4** — As a user viewing a brokered deal, I see an honest explanation rather than zeros.
- [ ] The financial tab states collection is handled by the developer and shows the reference schedule.
- [ ] No brokered deal appears in the overdue queue or generates overdue notifications.

---

### EPIC 8 — Commission engine *(no longer blocked)*

**E8-S1** — As an owner, I can configure commission rules by direction and scope.
- [ ] Rule captures direction, scope, participant role, basis, calculation type, value, trigger, effective dates.
- [ ] Resolution is most-specific-first: deal → project → developer → tenant.
- [ ] Overlapping rules at the same scope/direction/role/date are rejected.

**E8-S2** — As the system, I snapshot the rule applied at deal activation.
- [ ] The full rule definition is copied onto the entitlement.
- [ ] Changing or expiring a rule later does not alter any existing entitlement.

**E8-S3** **[BRK]** — As finance, I can track what each developer owes us per deal.
- [ ] Inbound entitlement created on brokered deals only.
- [ ] Default trigger: deal completed **and** down payment confirmed.
- [ ] States advance per `18`§8; claimed-and-unsettled past due date is flagged.

**E8-S4** — As finance, I can track what we owe each participant per deal.
- [ ] Agent, team leader, referrer and external partner are separate rows.
- [ ] `inbound_commission` basis computes correctly on brokered deals; `net_value` on own-inventory deals.
- [ ] Agents see only their own entitlements.
- [ ] Total outbound exceeding inbound warns without blocking.

**E8-S5** — As a manager, I can override commission on a specific deal.
- [ ] Deal-scoped rule or direct rate override, both requiring a reason and both snapshotted.

**E8-S6** — As finance, I can claw back commission when a deal is cancelled.
- [ ] Offsetting adjustment created; the original entitlement is never edited.

**E8-S7** — As an owner, I can see commission totals by direction and state.
- [ ] Expected / Accrued / Claimed-or-Payable / Settled / Overdue / Clawed-back; figures reconcile with per-deal detail.

---

### EPIC 9 — Dashboards and reporting

**E9-S1** — As the owner, I see a headline strip appropriate to my portfolio.
- [ ] **[OWN]** expected, collected, outstanding, overdue, active deals, unit status.
- [ ] **[BRK]** active deals, unit status, commission expected, commission settled, commission receivable, commission overdue from developers.
- [ ] Mixed: both blocks side by side, never summed.

**E9-S2** — As the owner, I can switch between month, quarter and year consistently.

**E9-S3** — As the owner, I can see the forecast relevant to my model.
- [ ] **[OWN]** cash collection forecast from the contractual schedule, cancelled deals excluded.
- [ ] **[BRK]** commission forecast by expected trigger or claimed due date.
- [ ] Overdue shown alongside, never merged into, either forecast.

**E9-S4** — As the owner, I can see sales and inventory by project, labelled by commercial model.

**E9-S5** — As any authorised user, I can export any financial view to CSV.
- [ ] Export respects filters and permissions; totals match on screen; every financial export audited.

**E9-S6** — As a user of a single-model tenant, I don't see irrelevant empty panels.
- [ ] A pure-brokered tenant sees no collections panels at all, rather than zeroed ones.

---

### EPIC 10 — Notifications, import/export, documents

**E10-S1** — As a user, I receive notifications for the events relevant to my model.
- [ ] Reservation expiry and stale leads in both models; installment due/overdue and bounced payments in own-inventory only.
- [ ] Notification failure never blocks the underlying transaction.

**E10-S2** — As ops, I can import and export core data with per-row error reporting.
- [ ] 3 invalid rows out of 100 → 97 imported, 3 reported with reasons.
- [ ] Payment import restricted to finance and to own-inventory deals.

**E10-S3** — As a user, I can attach documents to customers, deals and payments.
- [ ] Financial proof documents carry stricter access control; downloads audited.

---

## Part C — Release gating

**MVP is releasable to a pilot when** all ten epics pass acceptance for the commercial model(s) the pilot tenant actually uses. A pure own-inventory pilot does not need E5-S7/S8 or E8-S3 signed off; a pure brokered pilot does not need Epic 6 or 7 signed off. A mixed pilot needs everything.

**What changed from revision 1's gating.** Epic 8 was previously un-signoff-able because the commission configuration was unknown. It is now testable against the engine's capabilities rather than against one presumed arrangement: the acceptance criteria assert that *the range* is supported, and the tenant's own values are verified during configuration and UAT.

**Suggested pilot exit criteria:** one real tenant running one real project end to end — lead to activated deal to at least two payment or commission cycles — with the CEO dashboard reconciling against whatever spreadsheet they still keep in parallel. For a brokered pilot, the reconciliation target is their commission receivable log, not a collections sheet.
