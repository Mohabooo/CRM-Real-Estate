-- =====================================================================================
-- V6 — Authentication: tenant slugs and server-side sessions
--
-- Epic 1 built identity but deliberately left authentication unimplemented:
-- PrincipalResolver has no production implementation and TenantAuthenticationFilter
-- answers 401 to every protected path. Doc 23's resource map lists /auth (login, logout,
-- refresh, password reset) and doc 28 section 6 specifies email + password with
-- "server-side sessions or short-lived JWT". This migration supports the session choice.
--
-- Why sessions rather than JWT: E1-S3 requires that deactivating a user takes effect, and
-- a self-contained token keeps working until it expires. Revocation by deleting a row is
-- immediate and needs no denylist — which is the server state a stateless token was meant
-- to avoid in the first place.
-- =====================================================================================


-- =====================================================================================
-- tenants.slug — the key a person types at login
--
-- users.email is unique per tenant, not globally (C9, V2): the same person may hold
-- accounts in two tenants. So {email, password} alone does not identify an account, and
-- login needs a tenant key presented before any tenant is known. That is the same reason
-- invitations.token_hash is globally unique rather than tenant-scoped.
--
-- The alternative — look the email up across every tenant — needs a broad read over all
-- users and their password hashes, and leaks whether an address exists anywhere in the
-- installation. A slug the user already knows costs nothing and leaks nothing.
-- =====================================================================================

ALTER TABLE tenants ADD COLUMN slug TEXT;

-- Backfill from the name. Non-alphanumerics collapse to single hyphens; a name that
-- slugifies to nothing (all punctuation, or a non-Latin script) falls back to the id.
-- The row_number suffix separates tenants that share a name.
WITH slugged AS (
    SELECT id,
           nullif(btrim(regexp_replace(lower(name), '[^a-z0-9]+', '-', 'g'), '-'), '') AS base,
           row_number() OVER (
               PARTITION BY nullif(btrim(regexp_replace(lower(name), '[^a-z0-9]+', '-', 'g'), '-'), '')
               ORDER BY created_at, id) AS n
    FROM tenants
)
UPDATE tenants t
SET slug = CASE
        WHEN s.base IS NULL THEN 'tenant-' || left(replace(t.id::text, '-', ''), 12)
        WHEN s.n = 1 THEN left(s.base, 63)
        ELSE left(s.base, 57) || '-' || s.n
    END
FROM slugged s
WHERE s.id = t.id;

ALTER TABLE tenants ALTER COLUMN slug SET NOT NULL;

-- Global, not tenant-scoped: it is presented before any tenant is established, so it must
-- identify exactly one tenant across the whole installation.
ALTER TABLE tenants ADD CONSTRAINT uq_tenants_slug UNIQUE (slug);

-- Lowercase alphanumeric words joined by single hyphens. Constrained at the database so a
-- slug can be put in a URL or a subdomain later without re-validating every stored row.
ALTER TABLE tenants ADD CONSTRAINT chk_tenants_slug_shape CHECK (
    slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND length(slug) BETWEEN 2 AND 63);

COMMENT ON COLUMN tenants.slug IS
    'The company key typed at login, before any tenant is known. Globally unique because '
    'users.email is only unique within a tenant (C9).';


-- =====================================================================================
-- sessions
--
-- Two expiry columns, not one. expires_at slides forward as the session is used, so an
-- idle session dies; absolute_expires_at never moves, so a session that is used
-- continuously still ends. With only the sliding one, a stolen cookie kept warm by an
-- attacker would last forever.
--
-- The raw token is never stored. Only its SHA-256 is, exactly as for invitation tokens:
-- a database disclosure then yields nothing that can be replayed.
-- =====================================================================================

CREATE TABLE sessions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    user_id             UUID        NOT NULL,
    token_hash          TEXT        NOT NULL,

    issued_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ NOT NULL,
    absolute_expires_at TIMESTAMPTZ NOT NULL,

    revoked_at          TIMESTAMPTZ,
    revoked_reason      TEXT,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_user_id  UUID,
    updated_by_user_id  UUID,

    -- Global, like invitations.token_hash: the cookie is presented before any tenant is
    -- known, so the hash must identify exactly one session installation-wide.
    CONSTRAINT uq_sessions_token_hash UNIQUE (token_hash),

    CONSTRAINT uq_sessions_tenant_id UNIQUE (tenant_id, id),

    -- Composite: the session's user must belong to the session's tenant. A session is the
    -- one row that turns a cookie into a tenant, so a session pointing at another tenant's
    -- user would be a cross-tenant authentication.
    CONSTRAINT fk_sessions_user_same_tenant FOREIGN KEY (tenant_id, user_id)
        REFERENCES users (tenant_id, id) ON DELETE RESTRICT,

    CONSTRAINT chk_sessions_expiry_after_issue CHECK (expires_at > issued_at),

    -- The sliding expiry can never be pushed past the absolute one.
    CONSTRAINT chk_sessions_idle_within_absolute CHECK (expires_at <= absolute_expires_at),

    CONSTRAINT chk_sessions_last_seen_not_before_issue CHECK (last_seen_at >= issued_at),

    -- A revoked session always records why. "Signed out", "user deactivated" and
    -- "password changed" are different facts and an audit trail that cannot tell them
    -- apart is not much of one.
    CONSTRAINT chk_sessions_revocation_complete CHECK (
        (revoked_at IS NULL AND revoked_reason IS NULL)
        OR (revoked_at IS NOT NULL AND length(btrim(revoked_reason)) > 0))
);

-- Revoking every session a user holds, on deactivation or password change.
CREATE INDEX idx_sessions_user_live ON sessions (tenant_id, user_id)
    WHERE revoked_at IS NULL;

-- Sweeping sessions that have aged out.
CREATE INDEX idx_sessions_expiry ON sessions (expires_at)
    WHERE revoked_at IS NULL;

COMMENT ON TABLE sessions IS
    'Server-side sessions (doc 28 section 6). One row per signed-in browser; deleting or '
    'revoking the row ends access immediately, which a self-contained token cannot do.';
COMMENT ON COLUMN sessions.token_hash IS
    'SHA-256 of the cookie value. The cookie itself is never stored.';
COMMENT ON COLUMN sessions.expires_at IS
    'Idle expiry; slides forward on use, never past absolute_expires_at.';
COMMENT ON COLUMN sessions.absolute_expires_at IS
    'Hard ceiling set at issue and never moved, so a continuously used session still ends.';


-- =====================================================================================
-- Row-level security
-- =====================================================================================

ALTER TABLE sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE sessions FORCE ROW LEVEL SECURITY;

CREATE POLICY sessions_tenant_isolation ON sessions
    USING (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid);


-- =====================================================================================
-- Letting authentication find the tenant it is about to establish
--
-- The same bootstrapping problem V5 solved for the expiry sweep, in its other form. A
-- request arrives with a cookie and nothing else: to read the session that names the
-- tenant, a tenant must already be set. To set it, the session must already be read.
--
-- V5's answer applies unchanged — one narrow, named, SELECT-only exemption under
-- app.platform_task, cleared by TenantAwareDataSource on every connection it hands out so
-- no request can inherit it. Two tables are needed, each doing exactly one job:
--
--   tenants  — slug to id, at login. Restricted to ACTIVE tenants, so a suspended or
--              closed tenant cannot be signed into at all, enforced in the database
--              rather than only in a service that might forget to ask.
--   sessions — cookie hash to session, on every request. Restricted to sessions that are
--              neither revoked nor expired, so a revoked session is invisible to the
--              lookup path even if the service layer above it has a bug.
--
-- Everything the authenticated request then does happens under the ordinary policy, with
-- app.current_tenant_id set from the session row. No write is granted anywhere: both
-- policies are FOR SELECT, and inserting a session at login happens under the ordinary
-- policy once the tenant is known.
-- =====================================================================================

CREATE POLICY tenants_authentication_read ON tenants
    FOR SELECT
    USING (current_setting('app.platform_task', true) = 'authentication'
           AND status = 'active');

COMMENT ON POLICY tenants_authentication_read ON tenants IS
    'Read-only, active tenants only. Lets login resolve a slug to a tenant id before any '
    'tenant is established. Grants nothing on any other table.';

-- Invitation acceptance has the same shape as login and the same problem. The person
-- holding the token has no account and no tenant; the token is what names one. Until now
-- the lookup ran with no tenant established, which means the isolation policy matched
-- nothing and every valid invitation answered "not found" in production — while passing
-- its tests, where the connection is a superuser.
--
-- Narrower than it needs to be, on purpose: only a PENDING, unexpired invitation is
-- visible here. An accepted or expired token is invisible at the database, which is the
-- same answer the service gives and one fewer place for the two to disagree.
CREATE POLICY invitations_authentication_read ON invitations
    FOR SELECT
    USING (current_setting('app.platform_task', true) = 'authentication'
           AND accepted_at IS NULL
           AND expires_at > now());

COMMENT ON POLICY invitations_authentication_read ON invitations IS
    'Read-only, pending and unexpired invitations only. Lets an invitation token be '
    'resolved to its tenant before any tenant is established.';

CREATE POLICY sessions_authentication_read ON sessions
    FOR SELECT
    USING (current_setting('app.platform_task', true) = 'authentication'
           AND revoked_at IS NULL
           AND expires_at > now());

COMMENT ON POLICY sessions_authentication_read ON sessions IS
    'Read-only, live sessions only. Lets a cookie be resolved to its tenant before any '
    'tenant is established. A revoked or expired session is invisible here.';
