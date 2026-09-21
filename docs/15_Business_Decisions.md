# 15 — Business Decisions

**Revision 2 — September 2026.** Completely re-triaged for the multi-commercial-model direction, using a four-way classification rather than the previous blocking/non-blocking split.

**What changed and why.** Revision 1 classified eight questions as "MUST be answered before development." That was wrong, and wrong in a specific way worth naming: it treated *unknown customer configuration values* as if they were *undesigned product capabilities*. "What commission split does this brokerage use?" is not a development blocker — it is a value someone types into a settings screen. The blocker would have been "can the system express more than one split?", and that is a design question this revision answers. Revision 1 conflated the two and would have stalled development waiting for answers that were never needed.

---

## The four categories

1. **Product capability** — must be designed now, because the system must be able to express it. Resolved in this revision.
2. **Customer configuration** — a value a tenant sets up. Needs an answer before that tenant goes live, not before development starts.
3. **Phase-2 policy** — relates to functionality not in MVP. Can wait.
4. **True architectural blocker** — cannot proceed without it, because the answer changes the shape of the system irreversibly.

---

## Category 1 — Product capabilities (designed in this revision)

| # | Capability | Resolution | Where specified |
|---|---|---|---|
| P1 | Support both owned and brokered inventory | Two commercial models, configurable per project | `25` |
| P2 | Support a developer selling its own stock | Same as owned inventory — S1 and S3 collapse to one model | `25`§2 |
| P3 | Developer optional, required only where the project belongs to one | `developer_id` conditional on commercial model, DB-enforced | `25`§3, `16`§6–7 |
| P4 | Mixed portfolio in one tenant | Model on the Project, not the Tenant | `25`§3 |
| P5 | Support presence or absence of external commission | `direction` on a unified Commission entity | `26`§2, `16`§19 |
| P6 | Configurable commission basis | Five bases including `inbound_commission` | `26`§4 |
| P7 | Configurable commission timing | Six triggers; inbound default confirmed | `26`§5 |
| P8 | Configurable participants and splits | One Commission row per participant; internal or external payees | `26`§6 |
| P9 | Deal-level commission overrides | Deal-scoped rules or direct override, both snapshotted | `26`§7 |
| P10 | Historical snapshotting of the rule used | Mandatory `rule_snapshot` at activation | `26`§8, R-COMM-4 |
| P11 | Full collections for owned inventory | Unchanged three-layer model | `17`§6–9 |
| P12 | No assumed collection data for brokered inventory | Payments rejected on brokered deals; metrics not computed | R-PAY-0, R-BRK-1 |
| P13 | Know the down payment happened without reconciliation | Confirmation milestone + model-aware predicate | `25`§4, R-DP-1 |
| P14 | Commercial-model-aware cancellation | One workflow, policy at two points | `27`§5, `18`§9 |
| P15 | Templates never mutate live schedules | Template/instance separation retained | `16` Part A |
| P16 | Commission forecast distinct from cash forecast | R-FCT-4 alongside R-FCT-1 | `17`§9 |

**These are now settled.** None requires further business input to build.

---

## Category 2 — Customer configuration (per-tenant setup, not blockers)

Each needs an answer before a given tenant goes live. None blocks development. Sensible defaults exist except where noted.

| # | Configuration | Default | Needed by |
|---|---|---|---|
| C1 | Default commercial model for new projects | Tenant chooses at onboarding | Onboarding |
| C2 | Inbound commission rate per developer | None — set per developer | First brokered project |
| C3 | Inbound commission basis | `net_value` | First brokered project |
| C4 | Outbound rates per participant role | None — set per tenant | First deal |
| C5 | Outbound basis | `net_value` (OWN) / `inbound_commission` (BRK) | First deal |
| C6 | Outbound trigger | `manual` until chosen | First deal |
| C7 | Whether team-leader / referrer rules exist at all | None created | First deal |
| C8 | Discount approval threshold | None — set per tenant | Pilot |
| C9 | Whether discounts reduce commission basis | Implicit in C3/C5 choice | First deal |
| C10 | Grace days before overdue | 0 | Pilot |
| C11 | Aging bucket boundaries | 0-30 / 31-60 / 61-90 / 90+ | Pilot |
| C12 | Reservation expiry default and extension limit | Tenant setting | Pilot |
| C13 | Who may record payments | Finance + Operations | Pilot |
| C14 | Deal completion definition (BRK) | Manual, tenant-defined | First brokered deal |
| C15 | Clawback automatic or approval-gated | Approval-gated | First cancellation |

**On commission values specifically.** Targeted research found **no published Egyptian figures** for internal agent splits, team-leader overrides or referral rates, despite the practices existing (`26`§1). There is no convention to default to. This is why C2–C7 ship with minimal defaults and a configuration screen rather than opinionated presets — an invented default would be mistaken for a recommendation.

---

## Category 3 — Phase-2 policies (can wait)

| # | Policy | Needed by |
|---|---|---|
| Q1 | Refund computation policy for owned-inventory cancellation | P2 cancellation build |
| Q2 | Whether a grace/cure period applies before cancellation | P2 cancellation build |
| Q3 | Post-dated cheque usage — confirmed for UAE, unconfirmed for Egypt | P2 PDC build, if ever |
| Q4 | Unit transfer credit-carry-forward policy | Future |
| Q5 | Tiered commission thresholds | P2 tiered rules |
| Q6 | Inbound commission tranche structure (modelling 50–70% / 30–50% explicitly) | P2 |
| Q7 | Branch-level financial reporting requirements | P2 dashboards |
| Q8 | VAT treatment of commission | Any future invoicing feature |
| Q9 | Multi-currency | Future |

---

## Category 4 — True architectural blockers

**None.**

Every question that revision 1 marked as blocking has been resolved by the confirmed direction, resolved as a design decision in this revision, descoped from MVP, or reclassified as configuration:

| Rev 1 blocker | Status now |
|---|---|
| D1 — owns inventory or sells developer's? | **Answered: both.** Configurable per project (P1) |
| D2 — who collects customer money? | **Answered:** determined by commercial model (P1, P12) |
| D3 — how does the tenant learn actual collections? | **Descoped.** Developer reconciliation is out of MVP; the down-payment milestone replaces it (P13) |
| D4 — commission basis? | **Reclassified:** capability designed (P6), value configured (C3/C5) |
| D5 — inbound accrual trigger? | **Answered:** deal completed + down payment received, configurable (P7) |
| D6 — outbound payout trigger? | **Reclassified:** capability designed (P7), value configured (C6) |
| D7 — agent split rules? | **Reclassified:** capability designed (P8), values configured (C4/C7) |
| D8 — legally valid receipts? | **Descoped.** Out of MVP |

**What this means for the plan.** Development can begin across the whole MVP, including the commission engine, which revision 1 had gated. Epic 8 is no longer blocked — it builds a configurable engine, and the tenant's values arrive at configuration time.

---

## Genuinely open items

Not blockers, but worth stating so they are not mistaken for settled:

1. **No Egyptian internal-split evidence exists.** We support variation rather than a convention; if the pilot tenant's arrangement turns out to need a basis or trigger not in `26`§4–5, that is a real gap — worth checking against one real tenant's scheme before Epic 8 is signed off.
2. **Team-leader percentage overrides are unevidenced in MENA.** Supported, no default. If no pilot tenant uses them, that part of the engine goes untested in practice.
3. **"Deal completed" in the brokered model is tenant-defined** (C14). Since it gates inbound commission accrual, a vague definition will produce inconsistent commission timing. Worth pinning down per tenant in writing.
4. **A correction to earlier research:** the claim in `02`§7 that brokers handle 70–80% of developer sales volume could not be substantiated on re-examination and should not be cited (`26`§1).
