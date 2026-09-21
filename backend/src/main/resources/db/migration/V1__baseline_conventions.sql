-- =====================================================================================
-- V1  Baseline conventions
--
-- Epic 0 — technical foundation only. This migration deliberately creates NO business
-- tables: no tenants, users, leads, customers, projects, units, deals, payment plans,
-- installments, payments or commissions. Those belong to Epic 1 and later, each arriving
-- with the constraints that protect it (doc 28, section 5: constraints ship in the same
-- migration as the table they protect).
--
-- What this migration does establish is the money and rate representation, so that every
-- future table expresses amounts the same way and the NUMERIC(18,2) convention in
-- doc 22 section 8 cannot drift column by column.
--
-- Migrations are forward-only. There are no down scripts: a mistake is corrected by a new
-- migration, because rolling back a migration that financial data has already been written
-- through is not a safe operation.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- Money and rate domains
--
-- A PostgreSQL DOMAIN gives every amount column one definition and one place to change.
-- Amounts are NUMERIC(18,2); rates carry four decimal places because a rate is an input to
-- a calculation rather than a stored result, and pre-rounding it would bias every amount
-- derived from it (doc 17, Percentage).
--
-- Both are transparent to JDBC, so no driver or mapping configuration depends on them.
-- -------------------------------------------------------------------------------------

CREATE DOMAIN money_amount AS NUMERIC(18, 2);
COMMENT ON DOMAIN money_amount IS
    'Monetary amount, scale 2. All money columns use this domain. See doc 22 section 8.';

CREATE DOMAIN rate_percentage AS NUMERIC(18, 4);
COMMENT ON DOMAIN rate_percentage IS
    'Percentage rate, scale 4, expressed as written (5.0000 means 5%). See doc 22 section 8.';

-- -------------------------------------------------------------------------------------
-- Tenancy convention
--
-- Row-level security is applied per business table from Epic 1 onward, keyed on the session
-- variable below. It is recorded here as the agreed name so that every later policy refers
-- to the same setting rather than inventing its own.
--
--     Policy shape (Epic 1 onward, not created here):
--         ALTER TABLE <t> ENABLE ROW LEVEL SECURITY;
--         CREATE POLICY <t>_tenant_isolation ON <t>
--             USING (tenant_id = current_setting('app.current_tenant_id')::uuid);
--
-- Doc 28 section 4 requires two independent isolation layers: this one, and repository-level
-- scoping in application code. Neither is trusted alone.
--
-- The session variable is namespaced (contains a dot), which PostgreSQL permits to be set at
-- runtime without being declared in postgresql.conf. Reading it before it is set raises an
-- error rather than returning NULL, which is the behaviour we want: a query that runs with no
-- tenant established should fail, not silently match every row.
-- -------------------------------------------------------------------------------------
