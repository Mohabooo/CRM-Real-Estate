# 23 — API Design

**Revision 2 — September 2026.** Revised for the multi-commercial-model direction: commission endpoints are unified by direction, commission-rule configuration is added, payment and collections endpoints are model-scoped, and the deal resource gains down-payment-confirmation and completion actions.

**Subject:** The initial REST API structure, organized around business operations rather than table CRUD. Nothing here is implemented yet — this defines the contract development builds to.

**Base:** `/api/v1`. Tenant is derived from the authenticated session, never from a URL parameter or client-supplied header (`21`§5).

---

## 1. Design principles

1. **Resources are business objects, not tables.** There is no `/payment-allocations` endpoint, because allocation is a consequence of recording a payment, not something a client does independently.
2. **State changes are explicit sub-resource actions, not PATCHes to a status field.** `POST /deals/{id}/activate` rather than `PATCH /deals/{id} {"status":"active"}`. This makes preconditions, permissions and audit unambiguous, and matches `18` one-to-one.
3. **Money amounts are strings in JSON** (e.g. `"2850000.00"`), never floats (`22`§8).
4. **Dates are ISO-8601 date-only** where the domain is a date (`"2026-03-15"`), full timestamps only for events.
5. **Every mutating financial request accepts `Idempotency-Key`.**
6. **Computed figures are never accepted as input.** A client cannot post `outstanding` or `overdue`; the server computes them (`17`).
7. **List endpoints** support `page`, `page_size`, `sort`, and resource-specific filters, and return a consistent envelope with pagination metadata.
8. **Model-inapplicable capabilities return an explicit state, not empty data.** Requesting collections figures for a brokered deal returns `422` with `code: NOT_APPLICABLE_FOR_COMMERCIAL_MODEL`, never a payload of zeros (R-BRK-1). Clients render the explanation rather than a misleading figure.

---

## 2. Resource map

```
/auth                     login, logout, refresh, password reset
/me                       current user, permissions, preferences
/tenants/current          tenant settings (aging buckets, grace days, thresholds)
/branches
/users                    + /users/{id}/deactivate
/developers               + /developers/{id}/commission-rules
/projects                 + /projects/{id}/payment-plan-templates
/units                    + /units/{id}/block, /units/{id}/unblock, /units/{id}/history
/leads                    + /leads/{id}/assign, /convert, /disqualify
/customers                + /customers/{id}/deals, /payments, /statement
/activities
/reservations             + /{id}/confirm, /release, /extend, /convert
/deals                    + /{id}/activate, /confirm-down-payment, /complete, /cancel,
                            /discounts, /payment-plan, /installments, /payments, /commissions
/payment-plan-templates
/payment-plans            (instances; nested read under /deals/{id}/payment-plan)
/installments             (read + manual edit pre-activation)
/payments                 + /{id}/clear, /bounce, /void, /allocations   [own-inventory only]
/collections              read-only aggregates                           [own-inventory only]
/commission-rules         configuration
/commissions              unified, filtered by direction + state actions
/dashboards               /ceo, /branch, /agent
/reports                  /collections, /sales, /inventory, /commission (+ /export)
/notifications            + /preferences
/imports                  + /{id} status
/audit-events             read-only
```

---

## 3. Core endpoints in detail

### Inventory

```
GET    /projects?developer_id=&status=
POST   /projects
GET    /projects/{id}
PATCH  /projects/{id}
GET    /projects/{id}/units?status=available&type=&min_price=&max_price=
POST   /projects/{id}/payment-plan-templates

GET    /units?project_id=&status=&type=
POST   /units
PATCH  /units/{id}                    # list price, attributes — never status
POST   /units/{id}/block              { reason }
POST   /units/{id}/unblock
GET    /units/{id}/history
```
Unit status is never set directly by a client — it is a consequence of reservation and deal actions (`18`§2). `PATCH /units/{id}` rejects any attempt to write `status`.

### Reservations

```
POST   /reservations                  { unit_id, lead_id|customer_id, expires_at, deposit_amount }
POST   /reservations/{id}/confirm
POST   /reservations/{id}/release     { reason }
POST   /reservations/{id}/extend      { expires_at, reason }
POST   /reservations/{id}/convert     → creates a draft deal, returns it
```
`POST /reservations` returns **409 Conflict** with the current holder's identifier if the unit is already held (F2/A3).

### Deals

```
POST   /deals                         { unit_id, customer_id, agent_user_id, gross_value?, source_reservation_id? }
GET    /deals?status=&project_id=&agent_user_id=&branch_id=
GET    /deals/{id}                    # includes computed totals
PATCH  /deals/{id}                    # draft only
POST   /deals/{id}/discounts          { type, value, reason }
DELETE /deals/{id}/discounts/{did}    # creates a reversing discount, does not delete
POST   /deals/{id}/payment-plan       { template_id?, overrides? }  → generates draft schedule
POST   /deals/{id}/activate           → 201 with deal, unit, installments, commissions
POST   /deals/{id}/confirm-down-payment  { confirmed_on }   # brokered only — 422 on own-inventory
POST   /deals/{id}/complete           { completed_on }      # manual for brokered; automatic for own
POST   /deals/{id}/cancel             { reason, initiated_by }   (P2: creates a Cancellation)
GET    /deals/{id}/installments
GET    /deals/{id}/payments
GET    /deals/{id}/commissions
GET    /deals/{id}/statement          # customer statement of account
```

`POST /deals/{id}/activate` is the system's most consequential call. It atomically: validates R-PLAN-4, marks the unit sold, activates the plan, makes installments live, and creates both commission legs. It returns 422 with a structured diagnostic if the invariant fails, and 409 if the unit was taken in the meantime.

### Payment plans and installments

```
GET    /payment-plan-templates?project_id=
POST   /payment-plan-templates
PATCH  /payment-plan-templates/{id}
POST   /payment-plan-templates/{id}/archive

GET    /deals/{id}/payment-plan
POST   /deals/{id}/payment-plan/preview   { params }  → schedule without persisting
PATCH  /installments/{id}                  # pre-activation manual edit only
POST   /payment-plans/{id}/supersede       # P2 — new version
```
The **preview** endpoint matters for UX: agents need to show a customer three plan options side by side without creating records.

### Payments

```
POST   /payments                      { deal_id, amount, payment_date, method,
                                        reference_no?, proof_document_id?, status?,
                                        allocations? }        # Idempotency-Key required
                                      # 422 NOT_APPLICABLE_FOR_COMMERCIAL_MODEL if the deal is brokered (R-PAY-0)
GET    /payments?deal_id=&from=&to=&status=
GET    /payments/{id}
POST   /payments/{id}/clear
POST   /payments/{id}/bounce          { reason }
POST   /payments/{id}/void            { reason }              # FIN + BM
GET    /payments/{id}/allocations
POST   /payments/{id}/allocations     { allocations: [{installment_id, amount}] }  # manual re-allocation
```
Omitting `allocations` on create triggers auto-allocation (R-PAY-2). Supplying them performs manual allocation (R-PAY-3). Both paths validate against C5.

### Collections (read-only aggregates) — **own-inventory only**

All collections endpoints implicitly filter to `commercial_model = own_inventory` (R-SCOPE-2). A scope that resolves to brokered deals only returns `422 NOT_APPLICABLE_FOR_COMMERCIAL_MODEL`.

```
GET /collections/summary?period=month|quarter|year&as_of=&scope=project|customer|agent|plan&scope_id=
     → { expected, actual, outstanding_due, total_remaining, overdue, collection_rate }

GET /collections/forecast?horizon=next_month|next_quarter|next_year&scope=&scope_id=
     → { periods: [ { period, expected } ], excludes_cancelled: true }

GET /commissions/forecast?horizon=&direction=inbound
     → { periods: [ { period, expected } ] }   # the brokered-model equivalent (R-FCT-4)

GET /collections/aging?as_of=&scope=&scope_id=
     → { buckets: [ { label, from_dpd, to_dpd, amount, count, pct_of_total } ] }

GET /collections/overdue?as_of=&bucket=&scope=&scope_id=&page=
     → paginated overdue installments with customer, deal, dpd, amount
```
Every aggregate response includes the parameters it was computed with, so an exported figure is self-describing — important when a CEO forwards a number to someone else.

### Commissions — unified

```
GET  /commissions?direction=inbound|outbound&state=&deal_id=&counterparty_id=&from=&to=
GET  /commissions/{id}
POST /commissions/{id}/accrue        { reason? }                  # both directions
POST /commissions/{id}/claim         { claimed_on, due_date }     # inbound only
POST /commissions/{id}/mark-payable                               # outbound only
POST /commissions/{id}/settle        { amount, settled_on }       # both — receipt or payout
POST /commissions/{id}/clawback      { reason }                   # both
POST /commissions/{id}/override      { rate?, amount?, reason }   # deal-level override, snapshotted
```
One resource, direction-discriminated, matching the unified state machine in `18`§8. Direction-invalid actions return `422` (claiming an outbound entitlement, marking an inbound one payable). In MVP these are called by humans; in P2 `accrue` and `settle` are additionally called by the system on trigger events — the contract does not change, only the caller.

### Commission rules — configuration

```
GET    /commission-rules?direction=&scope=&scope_id=&active_on=
POST   /commission-rules      { direction, scope, scope_id, participant_role?, basis,
                                calc_type, value, tiers?, trigger, effective_from, effective_to? }
GET    /commission-rules/{id}
POST   /commission-rules/{id}/supersede  { new_rule }   # effective-dated replacement, never an in-place edit
POST   /commission-rules/{id}/deactivate
POST   /commission-rules/preview  { rule, sample_deal_value }  → computed amount, no persistence
```
`preview` exists because commission configuration is the highest-consequence setup screen in the product — a user should see what a rule will produce on a real deal value before saving it. Validation rejects `inbound` rules scoped to own-inventory projects and `inbound_commission` bases on own-inventory scopes.

### Dashboards and reports

```
GET /dashboards/ceo?period=month|quarter|year&as_of=
     → headline strip + sections (sales, collections, inventory, commission, projects)
GET /dashboards/branch?branch_id=&period=      # P2
GET /dashboards/agent                          # agent's own pipeline and payouts

GET /reports/collections?...&format=json|csv
GET /reports/sales?...
GET /reports/inventory?...
GET /reports/commission?...
```
Any report with `format=csv` writes an audit event (F14, BRD §5.20).

---

## 4. Conventions

**Errors** use a consistent structure:
```json
{ "error": { "code": "PLAN_INVARIANT_VIOLATION",
             "message": "Schedule total does not equal net value",
             "details": { "net_value": "2850000.00", "schedule_total": "2849999.84",
                          "difference": "0.16" } } }
```
Financial errors always include the numbers involved. A validation failure that says only "invalid" is useless to someone reconciling a schedule.

**Status codes:** 200/201 success · 400 malformed · 401/403 auth · 404 not found (also used instead of 403 to avoid leaking existence across tenants) · 409 conflict (unit taken, duplicate idempotency key with different payload) · 422 business-rule violation (invariant, insufficient permission for amount, plan mismatch) · 429 rate limited.

**Permissions** are enforced server-side per endpoint and per record scope (`21`§6). A 403 response never reveals whether the record exists.

**Versioning:** `/api/v1` with additive-only changes within the version. Money-affecting response changes require a new version.

**Pagination envelope:**
```json
{ "data": [...], "meta": { "page": 1, "page_size": 50, "total": 1284 } }
```

---

## 5. Out of scope for v1

Public lead-capture endpoint (MVP-Rec/P2, BRD Appendix C) · outbound webhooks (P2+) · developer inventory sync (Future) · payment gateway callbacks (out of scope per `05`§4) · GraphQL.
