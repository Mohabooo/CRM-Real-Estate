# Epic 0 — Implementation Report

**Status: complete and verified on hardware.**
Verified 20 September 2026 on macOS 26.2 (arm64), JDK 21.0.12.1 (Homebrew), Maven 3.9.11, Node 22.

This supersedes the interim report delivered in conversation, which recorded the backend as
written but unverified. Every figure below comes from a run that actually happened.

---

## 1. What was built

The technical foundation only: `Money` and `Percentage` exact-decimal value objects with
currency safety and string-only JSON; `MoneySplitter` (conserving split, floor and
remainder); `ScheduleDateCalculator` (month-end clamping that does not drift, because each
date is computed from the anchor rather than from its predecessor); `ScheduleInvariant`, the
production-grade `down + delivery + Σ installments == net_value` checker; the single API
error envelope with its error-code catalogue and global handler; correlation identifiers;
the `TenantContext` holder; the `CommercialModel` enum with its containment rule; a Flyway
baseline creating the `money_amount` and `rate_percentage` domains and no business tables;
build-breaking ArchUnit rules; and a React + MUI shell with working RTL and English/Arabic
switching over a typed API client.

## 2. What was deliberately not built

No tenants, users, authentication or roles. No leads, customers, developers, projects, units,
reservations or deals. No payment plans, installments-as-a-feature, payments, collections or
overdue handling. No commission engine. No dashboards or reporting. `GET /api/v1/platform/info`
reports `businessDomainsImplemented: []`, and `FlywayMigrationIT.no_business_tables_created`
fails the build if any business table appears — the absence is enforced, not merely intended.

## 3. Verification results

| Suite | Result |
|---|---|
| Unit, architecture, property (surefire) | **91 tests, 0 failures, 0 errors** |
| Integration (failsafe, real PostgreSQL 16) | **12 tests, 0 failures, 0 errors** |
| ArchUnit rules | 11, all enforced, build-breaking |
| jqwik properties | 5, 2000 tries each, including FIN-018 conservation |
| No-dependency financial check | 58 checks, 0 failures |
| Frontend | lint clean at `--max-warnings 0`, typecheck clean, 17/17 tests, production build 444.70 kB (143.60 kB gzip) |
| Full backend build | `BUILD SUCCESS`, 12.3 s |

Runtime, confirmed by hand against a real database: Flyway applied `v1 — baseline
conventions` to an empty schema; `GET /actuator/health` returned `UP` with the database
component `UP`; `GET /api/v1/platform/info` returned the expected identity and an empty
business-domain list.

The canonical fixture from `17`§5 is verified end to end: gross 3,000,000.00 → 5% discount →
net 2,850,000.00 → 10% down 285,000.00 → 5% delivery 142,500.00 → financed 2,422,500.00 → 32
quarterly installments of 75,703.12 with the 0.16 remainder landing on the final installment
(75,703.28). Sum of installments 1–31 is 2,346,796.72 and the schedule total is exactly
2,850,000.00.

## 4. How integration tests obtain PostgreSQL

`AbstractPostgresIT` tries three sources in order: a server named by `crm.it.db.url`;
Testcontainers; and an embedded PostgreSQL 16 run as a local process. Each is a real
PostgreSQL, so doc 28 §14's requirement holds in every case. Rationale and the isolation
guarantees are recorded in `28_Technical_Architecture.md` §14.

## 5. Defects found and fixed during verification

The first two were mine, introduced while writing Epic 0 and caught before they reached a
build. The last is a genuine product defect that the test suite found on its first real run.

1. **Non-parseable POM.** A decorative comment contained `--`, which is illegal inside an XML
   comment. Fixed, and every XML file in the project was re-parsed and scanned for the
   sequence to confirm it was the only instance.
2. **Over-broad architecture rule.** `reporting_is_read_only` forbade any dependency on a
   class whose name ends in `Service`, which would have false-positived against legitimate
   Epic 8 reads. The clause was removed.
3. **Arithmetic error in doc 29.** The stated sum of installments 1–31 was 2,347,796.72; the
   verified figure is 2,346,796.72. Corrected.
4. **Testcontainers could not reach Docker 29.** Docker 29 raised the minimum Engine API
   version; the client negotiated an older one and the daemon answered `/info` with an empty
   HTTP 400, which reads as "no Docker present". `api.version` is now pinned in the POM.
5. **Dependency clash.** `embedded-postgres` pulls a `commons-compress` that calls a
   `commons-lang3` method added in 3.17, while Spring Boot manages an older `commons-lang3`
   and the older jar won. The managed version is raised to 3.20.0.
6. **Unknown paths returned 500 instead of 404.** Since Spring Boot 3.2 an unmatched path is
   handed to the static resource handler, which raises `NoResourceFoundException` rather than
   the `NoHandlerFoundException` the envelope handled. It fell through to the catch-all and
   became an internal error, which is both wrong and more informative to a prober than a 404.
   A handler was added and `application.yml` now states that this API serves no static
   content.

## 6. Continuous integration

`.github/workflows/ci.yml` is present. Three jobs run on every push to `main`, every pull
request, and on demand.

**backend** — JDK 21 with a Maven cache, then `mvn clean verify`, which covers compilation,
the surefire suite (unit, architecture, property-based) and the failsafe suite
(Testcontainers integration tests) in one invocation rather than running the unit tests
twice. Three gates follow the build, because a green Maven run is not by itself proof that
the right things ran: integration tests must have executed and passed, so `-DskipITs` cannot
reach CI; `ArchitectureTest` must have executed with no violations, so the module boundary
and commercial-model containment rules cannot be quietly dropped; and the build log must show
no sign of a database fallback, so the documented Testcontainers path cannot rot behind an
embedded PostgreSQL that keeps the build green. A `docker info` precondition fails the job
early if the daemon is missing. Test reports upload on success and failure alike.

**frontend** — Node 22 with an npm cache, `npm ci` (which fails outright if `package.json`
and the lockfile have drifted), then lint, typecheck, test and build as four separate steps,
so a red build names the failing gate without anyone opening the log.

**migrations** — applies Flyway to an empty `postgres:16-alpine` service container and
validates the result, which catches a migration that only works because a developer's
database already contained the object it creates. It then asserts directly against the server
that `flyway_schema_history` is the only table, repeating `FlywayMigrationIT`'s claim
independently of the test harness.

## 7. Definition of Done

All items met: repository structure; backend skeleton; frontend skeleton; the financial
primitives with their invariants; unit, property-based and integration testing
infrastructure; build-breaking architecture rules; a Flyway baseline with no business tables;
continuous integration; local development documentation; and a verified green build of both
halves.

The CI workflow has been validated with `actionlint` and `shellcheck` (no problems reported),
its YAML parses, its embedded gate scripts compile, and every path and npm script it
references exists. It has not yet been observed running on GitHub, which will happen on the
first push.

## 8. Next step

Epic 1, on explicit instruction. Nothing in Epic 1 has been started, and no business domain
code exists in the repository.
