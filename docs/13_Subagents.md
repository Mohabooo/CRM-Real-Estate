# 13 — Subagents

**Subject:** Specialized subagent roles recommended for this project's ongoing work, and how they were actually used during this discovery pass. A platform note first, since it affects how to read the rest of this document.

---

## Platform note

This discovery was run in a Cowork session, whose available subagent types are fixed by the platform (`general-purpose`, `Explore`, `Plan`, `claude-code-guide`, `statusline-setup`) rather than user-definable `.claude/agents/*.md` files the way a local Claude Code CLI installation supports. **Specialization in this environment is achieved by briefing a `general-purpose` agent with a detailed, role-specific prompt**, not by creating a persistent new agent type. Everything below should be read as *recommended prompting patterns for future `general-purpose` agent dispatches on this project*, not as new agent types that now exist. If this project is later worked on from a local Claude Code CLI environment (which does support custom agent definitions), these role briefs translate directly into `.claude/agents/*.md` files.

---

## Roles actually used in this discovery, and how

Three parallel research subagents were dispatched during this pass (per the mission's suggested "Domain Analyst" / "Competitive Researcher" / "Financial Model Analyst" roles, merged pragmatically into three dispatches rather than five+ to control research overlap and cost):

1. **Competitive Researcher** — researched Egypt/MENA and international real-estate CRM/off-plan sales products, tagging findings [DOCUMENTED]/[MARKETING CLAIM]/[YOUR INFERENCE]. Output became `03_Competitive_Analysis.md`.
2. **Domain Analyst** (merged with early Financial Model Analyst scope) — researched brokerage operational pain points and Egyptian off-plan payment/commission norms. Output became `02_Brokerage_Domain_Research.md` and half of `04_Payment_Collection_Domain.md`'s evidence base.
3. **Financial Model Analyst** (dashboard/collections focus) — researched CEO dashboard conventions, AR/collections KPI patterns, and cancellation/refund precedents. Output became `09_CEO_Dashboard_Requirements.md` and the other half of `04`'s evidence base.

**Why three, not five separate agents per the mission's full suggested list:** the mission's suggested list (Domain Analyst, Competitive Researcher, Financial Model Analyst, Product Manager, Solution Architect, Requirements Auditor) includes roles that are better performed by the orchestrating session itself rather than delegated, per general guidance on subagent use: delegating *research* (bounded, verifiable, doesn't require carrying forward the full accumulated context of the discovery) is appropriate; delegating *synthesis and judgment* (Product Manager's MVP calls, Solution Architect's data-model decisions, Requirements Auditor's gap-finding) is not, because those tasks require holding the entire cross-referenced picture (BRD + all three research reports + the mission's own 24-section brief) in view at once — splitting that across agents would have produced inconsistent terminology and contradictory phase-tagging across the 14 deliverables. Those roles were performed directly by the orchestrating session instead, reading all three research reports together before writing `01`, `04`–`10`, `14`.

---

## Recommended roles for future work on this project

| Role | Responsibility | When to dispatch as a subagent vs. do directly |
|---|---|---|
| **Competitive Researcher** | Re-run targeted competitive research when a specific new competitor/product needs evaluating (e.g., a specific vendor the brokerage is evaluating) | Subagent — bounded, verifiable, citation-heavy research task |
| **Domain Analyst** | Deepen a specific unresolved domain question from `10_Business_Questions.md` (e.g., a follow-up research pass specifically on Egyptian PDC usage, Q19) | Subagent — same reasoning |
| **Financial Model Analyst** | Model a specific financial edge case in detail (e.g., work out the exact rounding/allocation algorithm for `04`§4's B4/D2 requirements) | Subagent for research; the actual algorithm design should be reviewed by whoever is holding the full data model in context |
| **Product Manager** | Convert new findings into MVP/backlog prioritization decisions | Do directly (or with the `mvz-scope-discipline-review` skill, `12`) — requires full cross-referenced context |
| **Solution Architect** | Review/evolve the entity model in `08_Data_Model.md` as implementation surfaces new needs | Do directly — schema decisions need full context of existing entities and their rationale, not a fresh research pass |
| **Requirements Auditor** | Periodically re-challenge assumptions in `10_Business_Questions.md` as they get answered, checking whether an answer invalidates a design decision elsewhere in `04`–`08` | Do directly — this is explicitly a cross-document consistency check, the opposite of a bounded research task |

## A note on "Requirements Auditor" specifically

The mission brief asked for a role that "challenges assumptions and identifies gaps." This discovery attempted to build that discipline into the *documents themselves* rather than as a separate audit pass at the end — every design decision in `04`–`08` is tagged **[DESIGN DECISION]** or **[OPEN QUESTION]** with explicit rationale, and `10_Business_Questions.md` exists specifically so nothing gets silently assumed. Recommend keeping this pattern (inline tagging + a living open-questions document) for future work on this project, rather than a periodic separate "audit report" that can drift out of sync with the documents it's auditing.
