# 12 — Claude Skills

**Subject:** Project-specific skills recommended for this brokerage CRM's ongoing discovery/requirements/design work, per the mission's instruction to use skills as "reusable specialist workflows rather than dumping the entire BRD into every prompt." Three skills are proposed for the user to save (via this session's skill-proposal mechanism); a few candidates from the mission's suggested list are deliberately **not** created, with reasoning.

---

## Proposed (saved via this session — see confirmation card)

### 1. `brd-requirements-gap-analysis`
**Purpose:** Repeatable workflow for analyzing a new business requirement against this brokerage CRM's BRD: produces an evidence-tagged gap table (existing coverage / what's missing / why it matters / proposed requirement / phase / complexity / dependencies), in the same structure used in `01_BRD_Gap_Analysis.md`.
**Why this project needs it, specifically:** the BRD is explicitly versioned ("v0.1, Draft") and will change again. Every future "the brokerage now also wants X" conversation should produce the same rigor this discovery did — not a fresh unstructured take each time — without re-explaining the methodology from scratch in the prompt.
**Reuse trigger:** any time a new feature/requirement is proposed for this CRM and needs to be checked against the current BRD before being accepted into scope.

### 2. `re-brokerage-payment-domain-brief`
**Purpose:** Encodes the domain knowledge this discovery gathered (Egyptian/MENA off-plan payment norms, commission tranche structure, the three/four-layer collections model, the brokerage-vs-developer money-flow distinction, the explicit gaps/inferences flagged in `02`/`04`) as a loadable domain brief, so future design work on payment plans, collections, or commission doesn't have to re-derive or re-research this from zero.
**Why this project needs it, specifically:** this is genuinely hard-won, source-cited domain knowledge (`02`, `04`) that took ~15+ combined research passes to assemble; without capturing it as a skill, the next person (or the next session) designing a related feature would either re-research it or — worse — guess without the same evidence base.
**Reuse trigger:** any design or requirements conversation touching payment plans, installments, collections, PDCs, or commission for this specific brokerage/market.

### 3. `mvp-scope-discipline-review`
**Purpose:** Reviews a proposed feature against this project's specific scope-discipline test (`05`§4: "is this brokerage CRM + collections visibility, or is it sliding into full developer/accounting ERP?") and against the Must/Should/Later/Not-Needed categorization convention established in `06_Proposed_MVP.md`.
**Why this project needs it, specifically:** the single biggest risk this discovery identified is scope creep toward a full ERP (`05`§4, `06` "Not Needed" list) — a lightweight, repeatable check-before-you-build workflow is the concrete mitigation, and it's specific enough to this project's actual boundary (not a generic "is this MVP" question) that a generic skill wouldn't capture it.
**Reuse trigger:** any time a new feature is proposed during MVP build-out or backlog grooming.

---

## Considered but not created

The mission brief suggested a longer list (`real-estate-domain-research`, `brokerage-crm-analysis`, `competitor-research`, `data-model-review`, `collection-reporting-analysis`, `commission-engine-analysis`). These were deliberately **not** turned into separate skills:

- **`real-estate-domain-research` / `competitor-research`** — largely covered by `re-brokerage-payment-domain-brief` above (the domain knowledge) combined with ordinary web research capability (already available every session without a skill). A dedicated "go research real estate CRMs" skill would mostly just re-state "use WebSearch thoroughly and cite sources," which doesn't need to be packaged — that's already how this discovery was executed.
- **`collection-reporting-analysis` / `commission-engine-analysis`** — the actual reusable substance of both is already captured inside `re-brokerage-payment-domain-brief` (§4 and §6 of `04_Payment_Collection_Domain.md` are exactly this content). Splitting them into separate skills would fragment one coherent domain brief into three overlapping ones for no benefit.
- **`data-model-review`** — genuinely useful in principle, but not yet exercised enough in this discovery to justify packaging (the mission's own guidance: "each skill should have a clear purpose," not "cover every conceivable future need"). Revisit once `08_Data_Model.md` has gone through at least one real review cycle with engineering — the skill would then be built from an actual observed repeated pattern, not speculation.
- **`brokerage-crm-analysis`** — too broad/generic; its intended content is really the union of the three proposed skills above.

This keeps the skill set at three, each with a distinct, non-overlapping purpose, consistent with the mission's explicit "do not create unnecessary skills" instruction.
