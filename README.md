# Real Estate Sales & Collections Platform

A multi-tenant web platform for real-estate sales, inventory, payment plans and collections,
with CRM capabilities. React + TypeScript frontend, Spring Boot REST API, PostgreSQL.

**Current state: Epic 0 — technical foundation.** No business domain is implemented. See
[What is and is not built](#what-is-and-is-not-built).

The requirements, domain model and architecture live in [`docs/`](docs/). Documents `15`
through `29` and `IMPLEMENTATION_READINESS.md` are the current source of truth; earlier
documents that were superseded carry a banner saying so.

---

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 | `java -version` should report 21.x |
| Maven | 3.9+ | Or use your IDE's bundled Maven |
| Node.js | 22 LTS | `node -v` |
| Docker | optional | Only for running the app against a local database. Tests do not need it |

On macOS with Homebrew:

```bash
brew install openjdk@21 maven node
brew install --cask docker    # then launch Docker Desktop once
```

If `java -version` does not report 21 after installing, add it to your shell profile:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
```

---

## Environment variables

Copy the example file and adjust if you need non-default ports:

```bash
cp .env.example .env
```

| Variable | Default | Used by |
|---|---|---|
| `DB_NAME` | `crm` | docker-compose, backend |
| `DB_USERNAME` | `crm` | docker-compose, backend |
| `DB_PASSWORD` | `crm` | docker-compose, backend |
| `DB_PORT` | `5432` | docker-compose |
| `DB_URL` | `jdbc:postgresql://localhost:5432/crm` | backend |
| `SERVER_PORT` | `8080` | backend |
| `SPRING_PROFILES_ACTIVE` | `local` | backend |
| `VITE_API_TARGET` | `http://localhost:8080` | frontend dev proxy |

`.env` is git-ignored. The defaults above are local-only and are not credentials for any real
environment.

---

## Running locally

### 1. Start PostgreSQL

```bash
docker compose up -d
docker compose ps          # wait for "healthy"
```

### 2. Run migrations

Migrations run automatically when the backend starts. To apply them without starting the app:

```bash
cd backend
mvn flyway:migrate \
  -Dflyway.url=jdbc:postgresql://localhost:5432/crm \
  -Dflyway.user=crm -Dflyway.password=crm
```

### 3. Start the backend

```bash
cd backend
mvn spring-boot:run
```

Available at <http://localhost:8080>:

- `GET /actuator/health` — overall health, including the database
- `GET /actuator/health/liveness` and `/readiness` — probes
- `GET /api/v1/platform/info` — build identity

### 4. Start the frontend

```bash
cd frontend
npm install     # first time only
npm run dev
```

Available at <http://localhost:5173>. API calls are proxied to the backend, so the browser
sees a single origin and no CORS configuration is needed in development.

---

## Running tests

### Backend

```bash
cd backend

mvn test                  # unit + architecture + property-based tests (no database needed)
mvn verify                # the above, plus integration tests
mvn verify -DskipITs      # skip integration tests for a fast loop
```

Integration tests need a real PostgreSQL 16. An in-memory database would not do: the
constraints this platform depends on — partial unique indexes, exclusion constraints,
`NUMERIC` domains, row-level security — either do not exist or behave differently elsewhere.

`mvn verify` needs no setup for this. Three sources are tried in order, and all three are
a real PostgreSQL:

1. **A PostgreSQL you already run**, if you set `crm.it.db.url`. See below.
2. **Testcontainers**, if a Docker daemon is reachable. This is the path CI takes.
3. **An embedded PostgreSQL 16**, started as an ordinary local process on a free port.
   No Docker, no install, no configuration.

Because of (3) the suite runs on a machine with no Docker at all — which also covers Docker
29, whose API the Testcontainers client cannot negotiate. The first run downloads the
PostgreSQL binaries once and caches them in `~/.m2`.

To use a database you already have, which skips both of the others:

```bash
mvn verify \
  -Dcrm.it.db.url=jdbc:postgresql://localhost:5432/crm \
  -Dcrm.it.db.username=crm \
  -Dcrm.it.db.password=crm
```

The database named in that URL is used only to connect. Each run creates its own database,
migrates it from empty, and drops it on exit, so the tests never see — or touch — whatever
you keep in `crm`. `CRM_IT_DB_URL` and friends work as environment variables if you would
rather not repeat the flags.

**No-dependency financial check.** The financial core is deliberately pure JDK, so it can be
compiled and run without Maven — useful when a Maven repository is unreachable:

```bash
cd backend
javac -d /tmp/fincheck \
  src/main/java/com/rescrm/platform/money/{CurrencyCode,CurrencyMismatchException,Money,Percentage}.java \
  src/main/java/com/rescrm/platform/errors/{ErrorCode,ApiException}.java \
  src/main/java/com/rescrm/finance/allocation/MoneySplitter.java \
  src/main/java/com/rescrm/finance/schedule/{Frequency,ScheduleDateCalculator,ScheduleInvariant}.java
javac -cp /tmp/fincheck -d /tmp/fincheck tools/FinancialCoreCheck.java
java -cp /tmp/fincheck FinancialCoreCheck
```

### Frontend

```bash
cd frontend

npm run lint
npm run typecheck
npm test
npm run build
npm run verify     # all four
```

---

## Full build

```bash
(cd backend && mvn clean verify)
(cd frontend && npm ci && npm run verify)
```

CI runs the same steps plus a job that applies migrations to an empty database.

---

## What is and is not built

**Built (Epic 0):**

- `Money` and `Percentage` value objects with exact decimal arithmetic, currency safety and
  string-only JSON serialization
- `MoneySplitter` — conserving split with floor-and-remainder semantics
- `ScheduleDateCalculator` — due dates with month-end clamping that does not drift
- `ScheduleInvariant` — the `down + delivery + Σ installments == net_value` checker
- Single API error envelope, global exception handling, error-code catalogue
- Request correlation identifiers, logged and echoed
- `TenantContext` holder (infrastructure for the tenancy design; not yet populated)
- `CommercialModel` enum with its containment rule
- Flyway baseline with `money_amount` and `rate_percentage` domains
- Build-breaking architecture rules (ArchUnit)
- React + MUI shell with working RTL and English/Arabic switching
- Typed API client with normalised errors

**Not built** — these arrive in Epic 1 and later, and their absence is deliberate:

Tenants, users, authentication and roles · leads and customers · developers, projects and
units · reservations · deals · payment plan templates and instances · installments as a
business feature · payments and collections · overdue management · the commission engine ·
dashboards and reporting.

Permanently out of scope (a decision, not a backlog): general ledger, accounts payable,
payroll, bank reconciliation, payment gateway processing, legal receipt generation, tax
engines, developer-side collection reconciliation.

---

## Project layout

```
.
├── backend/
│   ├── src/main/java/com/rescrm/
│   │   ├── platform/            cross-cutting: money, errors, observability, tenancy
│   │   ├── commercialmodel/     the only place commercial-model branching may live
│   │   ├── finance/             pure calculators: schedule dates, allocation, invariants
│   │   └── CrmApplication.java
│   ├── src/main/resources/db/migration/   Flyway, forward-only
│   ├── src/test/java/com/rescrm/
│   │   ├── architecture/        build-breaking ArchUnit rules
│   │   ├── fixtures/            the canonical financial fixture
│   │   └── integration/         Testcontainers-backed tests (*IT.java)
│   └── tools/                   no-dependency financial smoke check
├── frontend/
│   └── src/{api,app,components,features,i18n,theme,test}/
├── docs/                        requirements, domain model, architecture
├── .github/workflows/ci.yml
└── docker-compose.yml
```

---

## Conventions worth knowing before contributing

**Money never touches a float.** `double` and `float` are banned from financial packages by an
architecture test, and raw `BigDecimal` is banned from domain APIs. Use `Money`.

**Money crosses the API as a string.** `"2850000.00"`, never `2850000.00`. A JSON number
invites the client to parse it into a binary float.

**Rounding is half-up everywhere except one place.** The base installment floors, so the
remainder is non-negative and lands on the final installment. That exception is deliberate and
is documented in `docs/17_Financial_Business_Rules.md`.

**Financial history is never deleted.** Corrections are reversal and adjustment records.

**Derived values are never stored.** Outstanding, overdue and aging are computed on read, so a
failed job cannot silently corrupt a report.

**Migrations are forward-only** and ship with the constraints that protect the tables they
create.

**Commercial-model branching is contained.** If you find yourself writing
`if (commercialModel == ...)` outside the `commercialmodel` package, the build will fail. Add
a policy implementation instead.
