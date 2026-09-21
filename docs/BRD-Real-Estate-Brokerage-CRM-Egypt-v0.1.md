# Business Requirements Document (BRD)

## AI-Enabled Real Estate Brokerage Operations Platform (Egypt)

| Field | Value |
|--------|--------|
| **Document type** | Business Requirements Document (BRD) |
| **Version** | 0.1 (Draft) |
| **Status** | Foundation for PRD, architecture, data model, UI/UX, APIs, and roadmaps |
| **Primary market** | Egypt — small and medium real estate brokerage companies |
| **Product class** | Vertical SaaS — Brokerage Operations Platform (not generic CRM) |

---

## Document control

| Field | Value |
|--------|--------|
| **Intended audience** | Product, engineering, design, sales, pilot customers, future investors |
| **Guiding principle** | Operational simplicity and brokerage-native workflows over CRM breadth |
| **Language / market** | Arabic-friendly UX; Egypt-specific operational norms (EGP, local comms, compounds, off-plan/resale mix) |

### Assumptions (explicit)

- Initial delivery is **web-first, responsive, mobile-friendly**; native mobile apps are **future**.
- Target customers are **SMB brokerages** (useful design band ~5–150 active users per company; larger treated as enterprise later).
- **WhatsApp** is dominant for customer comms; full **WhatsApp Business Platform** integration is **strategically important but technically and commercially heavy** — phased.
- **AI** is **assistive** (draft/summarize/recommend), always with **human control** and **tenant-configurable** limits.
- This BRD defines **full vision** with **explicit phase tags** so scope can be cut without losing intent.

### Strategic differentiators (product thesis)

1. **Brokerage-native objects**: units, projects, reservations, visit logs tied to revenue motion — not “contacts + deals” only.
2. **Operational closure**: lead ownership, SLAs, stale detection, manager visibility — reducing leakage and excuse culture.
3. **Egyptian reality**: Arabic UI, phone formats, branch culture, heavy WhatsApp + spreadsheet migration paths.
4. **Fast onboarding**: templates, imports, opinionated defaults, optional white-glove onboarding.
5. **Assistive AI** where it reduces typing and speeds decisions — not “autonomous selling.”

---

# 1. Executive Summary

## 1.1 Vision

To become the **default operational backbone** for Egyptian real estate brokerages — a **multi-tenant SaaS** that replaces fragmented WhatsApp + spreadsheet operations with **structured lead-to-reservation workflows**, **trustworthy inventory context**, and **management-grade visibility**, augmented by **practical AI** that helps agents and managers move faster without replacing human judgment.

## 1.2 Mission

Deliver a **web-first, mobile-friendly Brokerage Operations Platform** that:

- Stops **lead leakage** through ownership, SLAs, and activity discipline.
- Centralizes **inventory and reservation** context for primary and resale motions.
- Gives owners and managers **honest operational dashboards** (not vanity charts).
- Integrates **communication workflows** (especially WhatsApp-related) in a phased, compliant way.
- Scales from **SMB** adoption to **enterprise-grade** capabilities over time without rewriting the core domain model.

## 1.3 Product goals

| Goal | Description | Horizon |
|------|-------------|---------|
| **Operational control** | Single system of record for leads, activities, units, reservations | MVP+ |
| **Adoption speed** | Onboard a pilot brokerage in days, not months | MVP |
| **Arabic-friendly** | RTL, localized copy, locale-aware formats | MVP (minimum: Arabic-ready UI patterns + content strategy) |
| **Revenue readiness** | Subscription billing, tenant isolation, auditability | Phase 2+ (billing may start manual) |
| **AI productivity** | Reduce manual note-taking and speed follow-ups | MVP (thin) → Phase 2+ |
| **Integration maturity** | CSV first; messaging and ads later | Phased |

## 1.4 Market opportunity

Egypt’s brokerage market is **fragmented and growing**: many small firms, high staff churn, high digital lead flow (Meta, referrals, walk-ins), and **low software maturity**. Owners pay for outcomes: **fewer lost deals**, **faster follow-up**, **clear accountability**, and **reservation hygiene**. A vertical platform that maps to **daily brokerage work** can win against generic CRMs that require expensive customization.

## 1.5 Problem statement

Brokerages run critical revenue processes across **WhatsApp threads**, **personal phones**, **shared spreadsheets**, and **memory**. This creates:

- **No authoritative pipeline** (disputes over lead ownership and follow-ups).
- **No inventory truth** near sales time (stale prices, wrong availability).
- **Weak reservation governance** (informal holds, missing documents, expiry confusion).
- **Poor management oversight** without micromanaging every chat.
- **Reporting that is either absent or manually fabricated** before investor/owner meetings.

## 1.6 Why current CRMs fail Egyptian brokerages (for this segment)

Generic CRMs (e.g., Salesforce, Zoho, HubSpot, Odoo) often fail **out of the box** because they:

- Model **B2B sales**, not **brokerage inventory + reservations + compound visits**.
- Treat WhatsApp as a peripheral channel rather than a **core operational surface**.
- Require heavy customization and admin skills **SMB brokerages do not have**.
- Produce **complexity costs** (licensing, implementation, ongoing admin) disproportionate to firm size.
- Under-serve **Arabic operational UX** expectations and local workflow habits.

**This product’s wedge:** “Brokerage operations” — lead + unit + reservation + accountability + reporting — **opinionated defaults**, **fast time-to-value**, **Egypt-first**.

---

# 2. Business problems to solve

| Problem | Description |
|---------|-------------|
| **Lead leakage** | Leads die silently: duplicates, unclear ownership, no next step, no escalation when idle. |
| **Follow-up chaos** | Agents rely on memory; managers cannot verify outcomes without meetings. |
| **WhatsApp dependency** | Customer truth lives in chats; handoffs break; history is not reconstructable. |
| **Spreadsheet operations** | Inventory/pricing in Sheets; versioning errors cause wrong offers. |
| **Lack of centralized tracking** | Parallel systems; no single timeline for client + unit. |
| **Weak management visibility** | Owners see results, not behaviors; bottlenecks are detected late. |
| **Poor reservation handling** | Informal holds, missing deposit proof, unclear expiry, disputes. |
| **Agent accountability** | Uneven work claims; uneven lead distribution; favoritism perceptions. |
| **Inventory inconsistency** | Units “available” while reserved; outdated payment plans; branch mismatch. |
| **Manual reporting** | Weekly reports built manually; inconsistent definitions. |
| **Weak customer history** | No consolidated profile across calls, visits, WhatsApp summaries, offers, documents. |
| **Delayed response times** | SLAs not enforced; hot leads cool off. |

---

# 3. Product vision & positioning

## 3.1 Product positioning

**Category:** Vertical SaaS — **Real Estate Brokerage Operations Platform**  
**Tagline (draft):** *From lead chaos to closed reservations — built for Egyptian brokerages.*

## 3.2 Competitive advantage (intended)

| Advantage | Explanation |
|-----------|-------------|
| **Domain-native workflow depth** | Reservations, units, visits aligned to brokerage reality |
| **Speed to value** | Opinionated defaults, templates, imports |
| **Operational accountability** | Ownership + SLAs + audit + dashboards |
| **Arabic-friendly UX** | RTL, localized terminology, culturally normal role naming |
| **Phased WhatsApp strategy** | Pragmatic: log → templates → deeper integrations |
| **Assistive AI** | Draft/summarize/digest with guardrails |

## 3.3 Egyptian market differentiation

- **Compound/project culture** and **off-plan** sales motions are central.
- **Branch-based hierarchies** are common; permissions must reflect reality.
- **Mixed inventory**: developer inventory + brokerage listings.
- **Communication norms**: WhatsApp-first; phone calls remain major.
- **Expectation management**: owners want control without software becoming surveillance theater.

## 3.4 Why different from Salesforce / Odoo / Zoho

- **Purpose-built objects** (unit, reservation, visit) and workflows, not a custom-objects project.
- **Lower configuration burden** for SMB: guided setup, templates.
- **Localized UX and onboarding** for Egyptian brokerage norms.
- **Pricing posture** aimed at SMB (commercial numbers belong to pricing strategy).

## 3.5 Operational wedge strategy

- **Wedge A:** Lead + ownership + SLA + activity timeline + stale detection.
- **Wedge B:** Unit/project inventory + reservation object + document checklist.
- **Wedge C:** Messaging integrations + marketing attribution + commissions depth.

## 3.6 Target customer profile

**Primary:** SMB Egyptian brokerage (single or multi-branch), ~5–80 agents, mixed primary/resale, heavy WhatsApp + spreadsheets today.  
**Secondary:** New brokerages seeking professional ops from day one.  
**Future enterprise:** Larger groups, stricter compliance, deeper integrations — **not MVP design driver**.

---

# 4. User personas & roles

Roles are **tenant-scoped** unless noted. Permissions: **coarse early**, granular later.

| Role | Description | Core needs |
|------|-------------|------------|
| **Platform Super Admin** | Vendor operator | Tenant provisioning, billing oversight, support impersonation (controlled), abuse prevention |
| **Brokerage Owner** | Founder/owner | Executive dashboards, alerts, reservation visibility, source ROI, exports |
| **Branch Manager** | Branch operations | Assignment oversight, stale queues, inventory hygiene, approvals |
| **Team Leader** | Pod lead | Team pipeline, coaching views, reassignment within team |
| **Sales Agent** | Frontline | Fast mobile web, my leads, next actions, quick logging, unit browse, reservation requests |
| **Operations User** | Back-office | Bulk updates, imports, document checklists, reservation status updates |
| **Finance User** | Finance / owner | Deposits, commission exports, payment milestones (phased) |
| **Marketing User** | Ads / campaigns | UTM/source tracking, lead ingestion, campaign performance |
| **Customer (future)** | Buyer/seller/renter | Status, document upload, scheduling — **Future/Enterprise** |

**MVP recommendation:** fixed roles + branch scope + “manager sees branch; agent sees own; owner sees all.” **Future:** custom roles, field-level permissions, advanced approvals.

---

# 5. System modules (full catalog)

**Phase tags:** **MVP-Core** | **MVP-Rec** | **P2** | **Future/Ent** | **Nice** | **HighCx** (high complexity)

Each module: **Purpose** · **Business value** · **Main workflows** · **Core entities (conceptual)** · **Actors** · **Dependencies** · **Phase** · **Complexity**

---

## 5.1 Authentication & Tenant Management

**Purpose:** Secure access; isolate broker data; onboard companies.  
**Business value:** Trust; prevents cross-tenant leaks.  
**Workflows:** signup/invite, login, reset, tenant creation, sessions.  
**Entities:** `Tenant`, `TenantSettings`, `User`, `Membership`, `InviteToken`.  
**Actors:** Super Admin, Owner, Ops.  
**Dependencies:** Email, secrets management.  
**Phase:** **MVP-Core**; custom domains **Future/Ent**.  
**Complexity:** Medium (security-sensitive).

## 5.2 User & Role Management

**Purpose:** Control visibility and actions inside a brokerage.  
**Workflows:** invite, assign role, deactivate, branch assignment, approvals (later).  
**Entities:** `Role`, `Permission` (or enums), `UserBranch`, `AuditLog`.  
**Phase:** **MVP-Core** fixed roles; custom RBAC **P2/Future**.  
**Complexity:** Medium → High (custom RBAC).

## 5.3 CRM / Leads

**Purpose:** System of record for demand.  
**Workflows:** create, dedupe warning, enrich, tag, disqualify, merge.  
**Entities:** `Lead`, `Contact`, `LeadSource`, `LeadStage`, `Tag`, `Activity`.  
**Phase:** **MVP-Core**.  
**Complexity:** Medium.  
**Note:** Egyptian phone variants + Arabic/English names.

## 5.4 Lead Assignment

**Purpose:** Clear ownership; fairness; speed.  
**Workflows:** manual assign, bulk reassign, reason; later round-robin/claim.  
**Entities:** `LeadAssignment`, `AssignmentRule` (later).  
**Phase:** **MVP-Core** manual; auto-distribution **P2** (**HighCx**).  
**Complexity:** Medium / High (auto).

## 5.5 Follow-ups & Activities

**Purpose:** Evidence of work; handoffs; coaching.  
**Workflows:** call, meeting, WhatsApp summary, email, note; next action.  
**Entities:** `Activity`, `ActivityType`, `Document` links.  
**Phase:** **MVP-Core**.  
**Manual early:** paste WhatsApp summary activity.

## 5.6 Calendar & Tasks

**Purpose:** Time-based coordination.  
**Workflows:** tasks, overdue alerts; calendar views; external sync later.  
**Entities:** `Task`, `CalendarEvent`.  
**Phase:** Tasks **MVP-Rec**; full calendar **P2**; external sync **Future/Ent**.  
**Complexity:** Medium.

## 5.7 Projects & Property Inventory

**Purpose:** Organize sellable/rentable stock.  
**Workflows:** project, phases, publish, branch visibility; developer entity optional.  
**Entities:** `Project`, `Phase`, `Developer` (optional), `Listing`, `Media`.  
**Phase:** **MVP-Core** manual + CSV; developer sync **Future/Ent** (**HighCx**).  
**Complexity:** Medium → High.

## 5.8 Units Management

**Purpose:** Atomic inventory item.  
**Workflows:** CRUD, status transitions, price/plan versioning, media.  
**Entities:** `Unit`, `UnitStatus`, `PricePlan` / payment matrix, `UnitHistory`.  
**Phase:** **MVP-Core** simplified; advanced matrices **P2**.  
**Complexity:** Medium → **HighCx** for full developer-style versioning.

## 5.9 Reservation Management

**Purpose:** Formalize hold → deposit → docs → confirmed/cancelled/expired.  
**Workflows:** request, approval (optional), deposit record, checklist, expiry, release unit.  
**Entities:** `Reservation`, `ReservationStatus`, `ReservationDocument`, `Approval`.  
**Phase:** **MVP-Rec**; payments depth **P2**.  
**Complexity:** Medium → High.  
**Strategic differentiator.**

## 5.10 Documents Management

**Purpose:** IDs, checks, developer forms, contracts.  
**Workflows:** upload, categorize, approve/reject, expiry reminders.  
**Entities:** `Document`, `DocumentType`, polymorphic `DocumentLink`.  
**Phase:** **MVP-Rec** basic; scan/OCR **P2/Future**.  
**Complexity:** Medium (**HighCx** security scanning).

## 5.11 Notifications & Reminders

**Purpose:** Drive behavior.  
**Workflows:** in-app, email digests, SMS optional, push (mobile future).  
**Entities:** `Notification`, `NotificationPreference`, scheduled jobs.  
**Phase:** **MVP-Rec** in-app + email; SMS **P2**.  
**Complexity:** Medium.

## 5.12 Dashboard & Reporting

**Purpose:** Role-based visibility.  
**Workflows:** daily/weekly review, exports.  
**Entities:** aggregates / optional `ReportingSnapshot` later.  
**Phase:** **MVP-Rec** basic dashboards; snapshots **P2**.  
**Complexity:** Medium → High at scale.

## 5.13 Analytics

**Purpose:** Deeper insights (velocity, cohorts, ROI).  
**Phase:** **P2**+; basic KPIs live under dashboards **MVP-Rec**.  
**Complexity:** **HighCx** if warehouse/BI.

## 5.14 AI Assistant Layer

**Purpose:** Productivity augmentation across modules.  
**Workflows:** draft follow-up, summarize timeline, suggest next action, weekly digest.  
**Entities:** `AiJob`, `AiPromptTemplate`, `AiUsageLog`, tenant AI settings.  
**Phase:** **MVP-Rec** thin (1–3 features); expand **P2**.  
**Complexity:** Medium product; **HighCx** if storing raw chats.  
**Guardrails:** human approval for outbound; tenant limits; BYOK **P2+**.

## 5.15 WhatsApp-related workflows

**W0 Log-based:** deep link + structured WhatsApp activity + paste summary — **MVP-Core/Rec**.  
**W1 Templates/reminders** via official APIs — **P2**, **HighCx**.  
**W2 Shared inbox** — **Future/Ent**, **HighCx**.  
**Deferred:** full omnichannel early.

## 5.16 Email workflows

**Purpose:** Notifications + formal comms; drips later.  
**Phase:** notifications **MVP-Rec**; marketing drips **P2**.  
**Complexity:** Medium.

## 5.17 Commission basics

**Purpose:** Transparency; payout alignment.  
**Phase:** **P2** minimum; advanced engine **Future/Ent** (**HighCx**).  
**Manual early:** export to sheet.

## 5.18 Marketing tracking

**Purpose:** Channel ROI.  
**Phase:** **MVP-Rec** source + UTM fields; ads APIs **Future/Ent** (**HighCx**).

## 5.19 Import / Export tools

**Purpose:** Spreadsheet migration; interoperability.  
**Phase:** **MVP-Core** minimal CSV.  
**Complexity:** Medium.

## 5.20 Audit logs

**Purpose:** Traceability for sensitive actions.  
**Phase:** **MVP-Rec** key objects; full enterprise audit **Future/Ent**.

## 5.21 Admin panel (tenant)

**Purpose:** Self-serve configuration.  
**Phase:** **MVP-Rec** minimal; deep settings **P2**.

## 5.22 Subscription / Billing

**Purpose:** Monetize SaaS.  
**Phase:** **P2** (manual invoicing acceptable in pilots).  
**Complexity:** Medium / **HighCx** with local payment diversity.

## 5.23 Mobile app considerations

**Purpose:** Native UX, push, optional offline.  
**Phase:** **Future/Ent**; **MVP** responsive web only.  
**Complexity:** **HighCx**.

## 5.24 Future integrations layer

Examples: WhatsApp Cloud API, Meta lead ads, telephony, accounting, SSO, webhooks.  
**Phase:** **P2+**; webhook platform **Future/Ent**.  
**Complexity:** **HighCx**.

### Module summary matrix

| Module | MVP-Core | MVP-Rec | P2 | Future/Ent | Complexity |
|--------|:--------:|:-------:|:--:|:----------:|------------|
| Auth + Tenant | ✓ | | | | Med |
| Users/Roles | ✓ | | custom | ABAC | Med→High |
| Leads CRM | ✓ | | | | Med |
| Assignment | ✓ | bulk | auto | | Med/High |
| Activities | ✓ | | | | Low/Med |
| Tasks | | ✓ | | | Med |
| Calendar | | light | full | sync | Med |
| Projects | ✓ | | adv | | Med |
| Units | ✓ | | version | sync | Med/High |
| Reservations | | ✓ | pay | legal | Med/High |
| Documents | | ✓ | OCR | | Med/High |
| Notifications | | ✓ | SMS | push | Med |
| Dashboards | minimal | ✓ | snap | BI | Med/High |
| Analytics | | basic | adv | warehouse | High |
| AI | | thin | expand | | Med/High |
| WhatsApp | log | polish | tmpl | inbox | High |
| Commissions | manual | export | basics | engine | High |
| Import/Export | ✓ | | | | Med |
| Audit | partial | key | full | | Med |
| Billing | manual pilot | | ✓ | ent | Med/High |
| Mobile apps | | | | ✓ | High |

---

# 6. Detailed business workflows

| Workflow | Goal | MVP scope | Manual acceptable |
|----------|------|-----------|---------------------|
| **Lead lifecycle** | Demand → qualified pipeline | capture, assign, stages, disqualify | dedupe = warn not auto-merge |
| **Lead assignment** | One accountable owner | manual + bulk | — |
| **Lead qualification** | Prioritize follow-up | fields, tags, next action | — |
| **Follow-up lifecycle** | Nothing falls through | next action + overdue + stale queue | — |
| **Daily agent workflow** | 10-minute clarity | my leads, sort by next action, quick log | — |
| **Reservation workflow** | Governed holds | statuses, dates, docs, expiry reminders | ops updates inventory |
| **Unit status workflow** | Inventory truth | manual transitions + warnings | auto from reservation **P2** |
| **Manager approval** | Sensitive actions | narrow: e.g. reservation extension only **MVP** | generalized engine **Future** |
| **Activity tracking** | Reconstruct without opening WhatsApp | types + WhatsApp summary | paste summary |
| **Reporting workflow** | Data-driven reviews | dashboards + CSV | PDF polish **P2** |
| **Notifications** | Proactive prompts | in-app + email critical | SMS **P2** |
| **Customer communication** | Consistent outreach | AI draft → user sends WA externally → log | full WA API **deferred** |

---

# 7. AI strategy & AI features

## 7.1 Principles

- Assistive, not autonomous; brokers responsible for client commitments.
- Human approval for high-risk outbound; tenant transparency when AI used.
- Minimize raw chat ingestion; prefer user-confirmed summaries early.
- Cost-aware: limits, BYOK **P2**, on-demand buttons vs always-on.

## 7.2 Feature catalog

| Feature | MVP-Rec | P2 | Future | Risk |
|---------|:-------:|:--:|:------:|------|
| AI-generated follow-up drafts | ✓ | | | tone errors |
| AI note cleanup/rewrite | ✓ | | | low–med |
| AI lead summaries | | ✓ | | accuracy |
| AI manager digest | | ✓ | | must cite facts |
| AI next-action recommendations | | ✓ | | trust if wrong |
| AI reservation summaries | | ✓ | | med |
| AI reporting “explain drop” | | ✓ | ✓ | hallucination |
| NL search / query | | | ✓ | security **HighCx** |

## 7.3 MVP AI (recommended)

Note cleanup + follow-up draft (edit required); optional Arabic/English tone.

## 7.4 Delayed

Autonomous chat agents, always-on ingestion, auto WhatsApp send without governance.

## 7.5 Privacy & compliance (direction)

PDPL-aware posture: minimization, retention, export/delete roadmap; audit on document download; encryption at rest.

---

# 8. Tracking & monitoring

| Feature | Phase | Notes |
|---------|-------|-------|
| Lead ownership + history | MVP-Rec | |
| Activity throughput | MVP-Rec | |
| Agent performance | P2 | frame as coaching; avoid toxic leaderboards default |
| Follow-up compliance | MVP-Rec | **Strategic** |
| Reservation pipeline | MVP-Rec | |
| Stale leads | MVP-Rec | **Strategic** |
| Branch comparisons | P2 | careful UX |
| Audit logs | MVP-Rec partial | expand later |
| Session management | P2 | |
| Attendance | Nice/Future | default **OFF** |
| GPS | Future | **visit verification only**, consent-based |
| Visit tracking | MVP-Rec light | compounds/projects |

**Not in scope:** continuous GPS surveillance, screen recording, keylogging.

---

# 9. Reporting & dashboard requirements

- **Owner:** reservations, funnel, sources, risks (stale, expiring reservations, inventory gaps).
- **Manager:** team pipeline, SLA breaches, activity mix, reassignment reasons.
- **Agent:** overdue leads/tasks, reservations in flight, quick actions.
- **KPIs:** leads created, time-to-first-action, time-in-stage, activities/lead, stale %, reservation conversion, unit aging.
- **Scheduled reports:** email weekly digest **MVP-Rec** basic; AI narrative **P2**.

---

# 10. Technical direction (lean, scalable)

- **Style:** Modular monolith (one deployable app, domain modules internally).
- **Multi-tenancy:** Shared Postgres + `tenant_id`; dedicated DB tier **Future/Ent**.
- **Backend:** Domain modules: `tenancy`, `identity`, `crm`, `inventory`, `reservations`, `documents`, `notifications`, `reporting`, `integrations`, `ai`, `billing`.
- **Frontend:** Responsive web, RTL-ready, component library, role layouts.
- **Database:** PostgreSQL; migrations; tenant-scoped indexes; append-only history for critical transitions.
- **Files:** S3-compatible; signed URLs; scanning **P2+**.
- **Notifications:** Queue workers; retries; preferences.
- **AI:** Provider adapter; async jobs; rate limits; usage logs.
- **Deployment:** Managed hosting + managed Postgres; staging; secrets manager; minimal IaC.
- **Security:** TLS, standard password hashing, server-side RBAC, CSRF (web), rate limits, backups.
- **Observability:** Error tracking + structured logs; expand later.

**Early non-goals:** microservices, multi-region active-active, plugin marketplace, full warehouse.

---

# 11. MVP definition

## 11.1 Absolute MVP

Multi-tenant + auth + membership · Leads + stages + ownership · Activities + next action + stale views · Projects + units (manual/CSV) · Minimal import/export · Basic manager views + exports · WhatsApp: deep link + WhatsApp-summary activity type.

## 11.2 Recommended MVP (sellable SaaS)

Absolute MVP + email + in-app notifications · Reservation v1 + expiry reminders · Audit on assignments/reservations/sensitive downloads · Mobile-friendly UX · Thin AI (note cleanup + follow-up draft) · Limited admin settings (sources/stages/tags).

## 11.3 Intentionally excluded early

Native apps, WA omnichannel inbox, generalized approval engine, full accounting, developer ERP sync, BI warehouse, plugin ecosystem.

## 11.4 Manual / “fake” early

CSV inventory refresh; commission via export; manual marketing spend; concierge weekly owner email during pilots if needed.

---

# 12. Risks & constraints

WhatsApp/Meta complexity; AI cost and hallucination; privacy incidents; adoption resistance; scope creep; incorrect money fields; multi-branch politics. Mitigations: phased WA, human-in-loop AI, least privilege + audit, wedge scope, narrow approvals MVP, configurable branch visibility.

---

# 13. Success metrics

**Adoption:** WAU agents, time-to-value, leads logged vs baseline, activities per lead.  
**Retention:** churn, expansion, support tickets/tenant.  
**Operational:** stale %, first-action time, reservation expiry rate, inventory conflict reports.  
**Sales efficiency:** lead-to-reservation where measurable.  
**AI:** weekly draft usage, complaint rate.  
**Revenue:** MRR, ARPA, onboarding fee attach.

---

# 14. Roadmap & phasing

1. **Discovery** — interviews, pilot LOI, lock Recommended MVP.  
2. **MVP** — build, 2–5 pilots, security hardening.  
3. **Operational enhancements** — tasks/calendar polish, inventory versioning, dashboards, audit.  
4. **AI expansion** — summaries, digests, next-action with guardrails.  
5. **Integrations** — WA templates, webhooks, ads, telephony optional.  
6. **Enterprise** — SSO, advanced RBAC, dedicated tenancy, customer portal.

---

# 15. Appendices

## Appendix A — Suggested entities (conceptual)

`Tenant`, `User`, `Membership`, `Role`, `Branch`, `Lead`, `Contact`, `LeadStage`, `LeadSource`, `Activity`, `Task`, `Project`, `Phase`, `Unit`, `UnitStatusHistory`, `Reservation`, `ReservationDocument`, `Document`, `Notification`, `ImportJob`, `AuditEvent`, `Campaign`, `CommissionRule` (later), `Subscription` (later), `AiJob` / `AiUsageLog` (later).

## Appendix B — ERD concepts

Tenant roots all data. User ↔ Tenant via Membership (+ branch). Lead 1..n Activity; Lead optional link to Unit/Project. Unit belongs to Project. Reservation links Lead + Unit with status timeline. Document polymorphic links.

## Appendix C — API direction

REST under `/api/v1/...` with tenant scoping; public lead capture with anti-spam **MVP-Rec/P2**; outbound webhooks **P2+**.

## Appendix D — Future integrations (deferred)

WhatsApp Cloud API inbox, Meta Lead Ads, telephony, accounting, developer inventory feeds, maps, e-sign, SSO.

## Appendix E — Future mobile

Same backend; push; optional offline read cache.

## Appendix F — Future AI roadmap

BYOK, dialect-tuned prompts, retrieval grounded in tenant docs (governed).

---

## Document history

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 0.1 | 2026-05-12 | Project | Initial BRD draft consolidated for repository storage |

---

*End of BRD v0.1*
