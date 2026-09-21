# 18 — Lifecycle State Machines

**Revision 2 — September 2026.** Revised for the multi-commercial-model direction. Changes from revision 1: a single unified Commission state machine replaces the two separate commission machines; Deal gains an explicit `completed` transition and a down-payment confirmation action; Cancellation is model-aware.

**Model shorthand:** **OWN** = `own_inventory`, **BRK** = `brokered_inventory`.
**Actors:** `AG` Agent · `TL` Team Leader · `BM` Branch Manager · `OPS` Operations · `FIN` Finance · `OWN̲` Owner/CEO · `SYS` System.
**Audit rule:** every transition touching money, inventory availability or ownership is audited.

---

## 1. Lead — model-agnostic

States: `new → assigned → contacted → qualified → reserved → converted` | `disqualified`

| From | To | Actor | Preconditions | Result | Audit |
|---|---|---|---|---|---|
| — | new | AG, OPS, SYS | valid phone or email | created; dedupe warning if match | No |
| new | assigned | BM, TL, OPS | owner active and in scope | owner set; SLA clock starts | **Yes** |
| assigned | contacted | owner AG | ≥1 activity logged | `first_contact_at` stamped | No |
| contacted | qualified | owner AG | interest captured | eligible for reservation | No |
| qualified | reserved | owner AG | reservation exists | reflects the hold | No |
| any active | converted | AG, OPS | customer exists or is created | linked; leaves funnel | **Yes** |
| any active | disqualified | owner AG, TL, BM | reason required | leaves funnel, retained | No |
| disqualified | assigned | BM, OPS | — | re-activation with reason | **Yes** |

---

## 2. Unit — model-agnostic

States: `available → reserved → sold` · `blocked` · returns to `available` on release or cancellation

| From | To | Actor | Preconditions | Result | Audit |
|---|---|---|---|---|---|
| — | available | OPS, import | belongs to a project; has list price | enters inventory | **Yes** |
| available | reserved | SYS on reservation confirm | no active reservation or deal | withheld from others | **Yes** |
| reserved | available | SYS on release/expiry | not converted | returns to inventory | **Yes** |
| reserved / available | sold | SYS on deal activation | no competing active deal | leaves sellable inventory | **Yes** |
| sold | available | SYS on cancellation approval (P2) | cancellation approved | re-enters inventory | **Yes** |
| available / reserved | blocked | OPS, BM | reason required | withheld | **Yes** |
| blocked | available | OPS, BM | — | returns | **Yes** |

**Hard invariant, both models:** at most one active Reservation and one active Deal per Unit, enforced in the database. A brokered unit can be double-sold as damagingly as an owned one — the tenant's reputation with the developer depends on this control.

---

## 3. Reservation — model-agnostic

States: `pending → confirmed → converted` | `released` | `expired` | `cancelled`

| From | To | Actor | Preconditions | Result | Audit |
|---|---|---|---|---|---|
| — | pending | AG | unit available; lead/customer present | hold requested; expiry set | **Yes** |
| pending | confirmed | BM, OPS (or auto) | deposit recorded if required | Unit → reserved | **Yes** |
| pending | cancelled | AG (own), BM | — | no unit change | **Yes** |
| confirmed | converted | AG, OPS | deal created from it | deal linked; OWN: deposit carries into the ledger. BRK: deposit is a recorded confirmation, not tenant cash | **Yes** |
| confirmed | released | AG, BM, OPS | reason required | Unit → available | **Yes** |
| confirmed | expired | SYS | past expiry, not converted | Unit → available; notify owner + BM | **Yes** |
| confirmed | confirmed (extended) | BM | within tenant limit | expiry extended | **Yes** |

---

## 4. Deal

States: `draft → active → completed` | `cancelled`

| From | To | Actor | Preconditions | Result | Audit |
|---|---|---|---|---|---|
| — | draft | AG, OPS | unit available/held by same party; customer exists | shell created; `commercial_model` copied from Project; values computed | **Yes** |
| draft | draft (edit) | AG, OPS | no active plan | price/discount/customer edits | **Yes** for discounts |
| draft | active | BM (or AG under threshold) | ① plan generated ② R-PLAN-4 holds ③ discount approved if required ④ unit not sold elsewhere | Unit → sold; installments live; **commissions created — inbound first (BRK only), then outbound** (R-COMM-2) | **Yes** |
| active | **down payment confirmed** | FIN, OPS | BRK only; confirmation received | `down_payment_confirmed_at` set; may satisfy inbound trigger | **Yes** |
| active | completed | SYS or FIN | **OWN:** all installments paid. **BRK:** contract signed/handover per tenant definition, set manually | deal closed; may satisfy inbound trigger (R-COMM-6) | **Yes** |
| active | cancelled | BM + OWN̲ (P2 via Cancellation) | cancellation approved | installments voided; Unit → available; commissions clawed back | **Yes** |
| draft | cancelled | AG, BM | — | no side effects | **Yes** |

**New in rev 2 — the down-payment confirmation action (BRK).** A discrete, manually-recorded milestone, not a ledger entry and not reconciliation (`25`§4, R-DP-1). It is the fact the confirmed inbound-commission trigger depends on.

**New in rev 2 — model-aware completion.** In OWN, completion is mechanical: the schedule is fully paid. In BRK the tenant does not know when the customer finishes paying, so completion means "our part of the sale is done" and is set by a human per the tenant's definition. Without this, a brokered deal could never reach `completed` and the confirmed inbound trigger would never fire.

---

## 5. CustomerPaymentPlan — model-agnostic

States: `draft → active → superseded` | `closed` | `cancelled`

| From | To | Actor | Preconditions | Result | Audit |
|---|---|---|---|---|---|
| — | draft | AG, OPS | deal in draft; template or custom terms | instance with frozen terms | **Yes** |
| draft | draft (regenerate) | AG, OPS | no allocations | schedule regenerated | **Yes** |
| draft | active | SYS on deal activation | R-PLAN-4 holds | installments live | **Yes** |
| active | superseded | BM + FIN (**P2**) | replacement version created | unpaid installments voided; paid history preserved | **Yes** |
| active | closed | SYS (OWN) / FIN (BRK) | OWN: all paid. BRK: manual, on deal completion | — | **Yes** |
| active | cancelled | SYS on deal cancellation | — | unpaid installments voided | **Yes** |

**Invariant:** exactly one `active` plan per deal. Templates are never mutated by any of this — the instance is a frozen copy (`16` Part A).

---

## 6. Installment

States: `scheduled → partially_paid → paid` · `void` · (`overdue` derived, never stored)

| From | To | Actor | Preconditions | Result | Audit |
|---|---|---|---|---|---|
| — | scheduled | SYS generation | plan activation | live obligation | **Yes** (per plan) |
| scheduled | partially_paid | SYS on allocation | `0 < allocated < expected` | status recomputed | **Yes** via allocation |
| scheduled/partially_paid | paid | SYS on allocation | `allocated ≥ expected` | status recomputed | **Yes** via allocation |
| paid/partially_paid | back | SYS on payment reversal | payment voided/bounced | allocations reversed; status recomputed | **Yes** |
| any | void | SYS on supersession or cancellation | — | excluded from aggregates; history retained | **Yes** |

**BRK note:** brokered installments remain `scheduled` for their whole life because payments are not recorded (R-PAY-0). Overdue is **not computed** for them (R-BRK-1) — the UI says collection is handled by the developer rather than showing a zero.

**On `overdue`:** deliberately not a stored state. Storing it would need a nightly job mutating financial rows, and any failure would silently corrupt collections reporting.

---

## 7. Payment — **OWN only**

States: `pending → cleared` | `bounced` · `cleared → void`

| From | To | Actor | Preconditions | Result | Audit |
|---|---|---|---|---|---|
| — | pending | FIN, OPS | **deal is OWN** (R-PAY-0); active; amount > 0 | recorded, not yet collected | **Yes** |
| — | cleared | FIN, OPS | as above, immediate instruments | allocation runs | **Yes** |
| pending | cleared | FIN | confirmation recorded | allocation runs | **Yes** |
| pending | bounced | FIN | returned/failed | no allocation; notify agent + BM | **Yes** |
| cleared | void | FIN + BM | correction; reason required | allocations reversed; installments recomputed | **Yes** |
| bounced | cleared | FIN | re-presented and honoured | allocation runs | **Yes** |

Payments are never deleted; corrections are `void` plus a new payment.

---

## 8. Commission — unified (replaces the two rev-1 machines)

One machine, direction-discriminated. Some states apply to one direction only.

States: `expected → accrued → (claimed | payable) → settled` · `clawed_back` · (`overdue` derived, inbound)

| From | To | Direction | Actor | Preconditions | Result | Audit |
|---|---|---|---|---|---|---|
| — | expected | both | SYS on deal activation | rule resolved and **snapshotted** (R-COMM-4) | amount computed | **Yes** |
| expected | accrued | inbound | SYS or FIN | trigger met — default: deal `completed` **and** `down_payment_satisfied` (R-COMM-6) | tenant has earned it | **Yes** |
| expected | accrued | outbound | SYS or FIN | trigger met per rule (R-COMM-7) | participant has earned it | **Yes** |
| accrued | claimed | **inbound only** | FIN | tenant has billed the developer | claim date and due date recorded | **Yes** |
| claimed | settled (partial/full) | inbound | FIN | receipt recorded | `settled_amount` increased | **Yes** |
| accrued | payable | **outbound only** | FIN | approved for payout run | included in batch | **Yes** |
| payable | settled | outbound | FIN | payout executed | `settled_amount` set | **Yes** |
| any | clawed_back | both | FIN + BM | deal cancelled (R-COMM-9) | offsetting `CommissionAdjustment`; original preserved | **Yes** |

**Derived `overdue` (inbound):** claimed and unsettled past `due_date`. Computed, never stored.

**Model behaviour.** OWN deals create outbound rows only — the inbound path is simply unused. BRK deals create one inbound row plus outbound rows, inbound computed first so that outbound rules using `inbound_commission` as basis have a value to work from (R-COMM-2).

**Why one machine instead of two:** the lifecycles are the same shape — earn, approve, settle, with clawback — and the rule-resolution, snapshotting and adjustment logic is identical. Two machines meant duplicating that logic and made "the commission position on this deal" a two-table question. The only genuine divergence is `claimed` vs `payable`, which a direction check handles.

---

## 9. Cancellation — model-aware (P2)

States: `requested → in_grace → approved → settled` | `rejected`

| From | To | Actor | Preconditions | Result | Audit |
|---|---|---|---|---|---|
| — | requested | AG, OPS, FIN | deal active; `initiated_by` recorded (`tenant`/`developer`/`customer`) | reason captured | **Yes** |
| requested | in_grace | SYS | **OWN only**; tenant grace policy applies | grace clock runs; customer may cure | **Yes** |
| requested/in_grace | approved | BM + OWN̲ | **OWN:** refund computed per configured policy (R-CAN-1). **BRK:** developer's decision recorded; refund noted if known | side effects per R-CAN-2 | **Yes** |
| requested/in_grace | rejected | BM | — | deal remains active | **Yes** |
| approved | settled | FIN | **OWN:** refund paid. **BRK:** outcome recorded | closed | **Yes** |

**Common core (both models):** deal → cancelled, remaining installments voided, unit → available, commissions clawed back, fully audited.
**Configurable / model-specific:** grace period (OWN), refund computation (OWN), whether clawback is automatic or approval-gated (both). In BRK the tenant is recording someone else's decision, not making one (`27`§5).

---

## 10. Cross-cutting rules

1. **No transition bypasses its side effects.** Unit status, installment voiding and commission clawback execute in the same transaction as the triggering transition.
2. **Every audited transition records** actor, timestamp, from-state, to-state, entity and reason where required.
3. **Derived states are never persisted** — installment `overdue`, inbound-commission `overdue`, aging buckets.
4. **Reversal over deletion** everywhere in the financial path.
5. **Commercial model is read, never written, by these machines.** It is set once at project creation and copied to the deal; no transition changes it.
