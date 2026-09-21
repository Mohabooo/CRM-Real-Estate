# 11 — MCP Recommendations

**Subject:** MCP (Model Context Protocol) servers that could materially help this project's ongoing research/design/development workflow, checked against this account's live connector registry on 2026-09-19. Recommendation is deliberately minimal — the mission brief is explicit: "prefer the minimum useful MCP set," "never install random MCPs just because they exist." No credentials, tokens, or secrets are included or should ever be committed to the `crm` repository/folder.

---

## Already connected in this workspace (no action needed)

### Atlassian MCP (Jira, Confluence, Bitbucket, Loom)
- **Purpose:** search/read/update Jira issues, Confluence pages, Bitbucket repos.
- **Why this project needs it:** the 14 deliverables in this discovery are natural candidates to become Confluence pages (or their gaps/questions to become Jira epics/stories) once the brokerage's team wants to plan sprints from them — e.g., `10_Business_Questions.md`'s items map cleanly to Jira tickets blocking specific epics from `07_Functional_Requirements.md`.
- **Security considerations:** already authenticated under this account's existing Atlassian permissions; no new credential exposure. Standard care: don't paste customer PII (Egyptian national IDs, payment details) into Jira/Confluence unless that's an approved system of record for such data.
- **Verification status:** official (Atlassian's own Rovo/MCP integration).
- **Credentials required:** already configured at the account level.
- **Scope:** org/user-scoped (not project-specific) — already available in this session.
- **Recommendation:** **use as-is**, no install action needed. Suggest using it to create a Jira epic per `06_Proposed_MVP.md` "Must Have" line item when the team is ready to start sprint planning.

### Postman MCP
- **Purpose:** manage Postman collections, specs, mocks, environments.
- **Why this project needs it:** BRD Appendix C already commits to a REST API under `/api/v1/...`. Once the money-model endpoints (installments, payments, commission) in `07`/`08` are designed, Postman is a natural place to spec and mock them before backend implementation — particularly useful for the Finance/Ops-facing endpoints given how numerically sensitive they are (worth testing edge cases like partial payments and rounding before writing code).
- **Security considerations:** don't store real customer/financial data in shared Postman mock responses; use synthetic fixtures.
- **Verification status:** official (Postman's own MCP).
- **Credentials required:** already configured at the account level.
- **Scope:** user/workspace-scoped.
- **Recommendation:** **use as-is**, no install action needed. Not urgent until API design begins (post-MVP-definition, i.e., after this discovery phase).

---

## Recommended to add

### GitHub — via `gh` CLI, not a new MCP connector
- **Finding:** no dedicated GitHub MCP connector was found in this account's registry search (searched "github", "git", "pull request", "issues").
- **Why this project needs it:** once implementation starts, PR review, issue tracking, and repo management will be needed for the actual codebase (separate from this `crm` documentation folder).
- **Recommendation:** for a Claude Code-based engineering workflow, use the `gh` CLI directly via the shell (already available in a standard Claude Code environment) rather than installing a GitHub MCP — the system guidance for this environment already prefers `gh` over MCP for GitHub operations. **Do not install a redundant GitHub MCP connector** if `gh` CLI access is available where implementation happens.
- **If a GitHub MCP is later needed** (e.g., for a non-technical PM to review PRs from within Cowork without CLI access), re-run this same registry search at that time — availability may change.

### A Postgres-hosted-DB MCP (Neon, Supabase, or PlanetScale) — defer, don't install now
- **Purpose:** schema comparison/migration tooling, direct query access, branch-per-feature database workflows.
- **Why this project might need it:** BRD §10 already commits to PostgreSQL; the money-model schema in `08_Data_Model.md` will need real migrations once implementation starts (e.g., Neon's `compare_database_schema` / `complete_database_migration` tools map directly onto evolving the Installment/Payment/Commission tables safely).
- **Security considerations:** **significant** — this would give an AI agent direct read/write access to a database that, per `07` N1/N7, will hold financial and customer PII. Recommend **project-scoped credentials with least-privilege read access** for any research/reporting use case, and **never** connect a production database directly to an MCP without a staging/sandbox tier first, given the "financial data integrity" non-functional requirement (`07` N4) — an accidental write from an agentic session is a realistic risk class this specific requirement exists to prevent.
- **Verification status:** all three (Neon, Supabase, PlanetScale) are official vendor-published connectors in this account's registry.
- **Credentials required:** yes — database connection/API credentials, must be provisioned specifically, never reused from another environment.
- **Scope:** should be **project-scoped**, not user-scoped, and ideally environment-scoped (staging only) if connected at all.
- **Recommendation:** **do not install yet.** This discovery phase (per the mission's explicit instruction, §23) does not create a database. Revisit once implementation begins and the team has chosen a specific Postgres host; at that point, connect with a read-only role for schema/reporting work and keep migration-execution credentials out of any AI-agent-accessible connector.

### Figma MCP — defer until UI design phase
- **Purpose:** pull design context, screenshots, variable definitions from Figma files into code/diagrams.
- **Why this project might need it:** the CEO dashboard (`09`) and payment-plan entry UI are exactly the kind of numerically dense screens worth prototyping in Figma before building — a Figma MCP would let implementation translate approved mockups directly into code.
- **Security considerations:** low — Figma files for this project are unlikely to contain customer PII if used purely for UI mockups; standard care if real sample data is pasted into mockups.
- **Verification status:** official (Figma's own MCP), already available in this session's skill list (`figma-*` skills present but the Figma MCP connector itself is not yet connected).
- **Credentials required:** yes — Figma OAuth.
- **Scope:** user-scoped is fine (design files, not sensitive data).
- **Recommendation:** **do not install yet** — this discovery phase produces no UI. Install when the team starts wireframing the payment-plan/CEO-dashboard screens (naturally follows `06_Proposed_MVP.md`'s Must-Have list).

---

## Explicitly not recommended

- **A separate project-management MCP (Linear, ClickUp, monday.com, Todoist)** — the account already has Atlassian (Jira) connected. Installing a second PM tool creates exactly the kind of fragmented-source-of-truth problem this entire project exists to eliminate for the brokerage (see `02`§1's Excel/spreadsheet-fragmentation findings — the same anti-pattern applies to the *build team's* tooling). Use Jira.
- **A separate documentation/wiki MCP (Notion)** — this session already has a bound claude.ai Project ("CRM") serving as the durable knowledge base, plus Confluence via the already-connected Atlassian MCP. A third documentation system is unnecessary sprawl.
- **A general-purpose "internal tools" aggregator (Natoma, Fibery)** — too broad for this project's actual needs; the mission brief specifically warns against installing MCPs "just because they exist."
- **Any finance/accounting MCP (Digits, Stripe, Plaid Developer Tools)** — per `05`§1/§4, this system is explicitly **not** building a general ledger, bank reconciliation, or payment-processing capability. Connecting a live financial-data or payment MCP to an AI-agent session at this stage would be a scope and security mismatch with the recommended interpretation (C, not D). Revisit only if `10_Business_Questions.md` Q2/Q23 resolve toward the brokerage directly processing payments at meaningful volume — and even then, this belongs in the production application, not in an AI coding/discovery session.

---

## General security posture for any MCP connected to this project going forward

1. Never commit API keys, tokens, or connection strings into the `crm` repository/folder — the mission brief explicitly requires this, and it applies to every recommendation above.
2. Prefer project-scoped or environment-scoped credentials over account-wide ones for anything touching financial/customer data (database, payments) — general-purpose research/documentation tools (Atlassian, Postman, Figma) are lower-risk and fine at user/workspace scope.
3. Any connector touching real customer data (name, phone, payment history, national ID references) should be re-evaluated against Egypt's data-privacy posture already referenced in BRD §7.5 (PDPL-aware direction) before being connected to an AI-agent session — this applies to future production-database or CRM-data connectors, not to the documentation tooling recommended above.
