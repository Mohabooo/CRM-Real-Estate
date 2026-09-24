package com.rescrm.identity.domain;

import com.rescrm.commercialmodel.CommercialModelCodes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The company using the system (doc 16, section 1).
 *
 * <p>The isolation root: it carries no {@code tenant_id} because its identity is the tenant.
 * Every other row in the system reaches it directly or transitively, and its row-level
 * security policy matches on {@code id} rather than {@code tenant_id}.
 */
@Entity
@Table(name = "tenants")
public class Tenant {

    /** Matches {@code chk_tenants_slug_shape} in V6 exactly. */
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    /**
     * The key a person types at login, before any tenant is established.
     *
     * <p>Needed because {@code users.email} is unique per tenant rather than globally (C9):
     * the same person may hold accounts in two tenants, so an address alone does not name an
     * account. Not updatable — it is a credential-adjacent identifier, and changing one
     * silently locks out everybody who knows the old value.
     */
    @Column(name = "slug", nullable = false, updatable = false)
    private String slug;

    @Column(name = "status", nullable = false)
    private String status;

    /**
     * Held as an opaque code, never as {@code CommercialModel}.
     *
     * <p>Doc 28 section 2 rule 5 keeps the enum inside its own package so no module can branch
     * on it. Validation goes through {@link CommercialModelCodes}, which is the single
     * authority on which codes exist.
     */
    @Column(name = "default_commercial_model", nullable = false)
    private String defaultCommercialModel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings", nullable = false)
    private String settings;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @Column(name = "updated_by_user_id")
    private UUID updatedByUserId;

    protected Tenant() {
        // JPA
    }

    private Tenant(UUID id, String name, String slug, String defaultCommercialModel,
                   String settings) {
        this.id = id;
        this.name = requireText(name, "tenant name");
        this.slug = requireSlug(slug);
        this.status = TenantStatus.PROVISIONING.code();
        this.defaultCommercialModel = CommercialModelCodes.requireValid(defaultCommercialModel);
        this.settings = settings == null || settings.isBlank() ? "{}" : settings;
    }

    /** A new tenant always starts in {@code provisioning} — never directly active. */
    public static Tenant provision(String name, String slug, String defaultCommercialModel,
                                   String settings) {
        return new Tenant(UUID.randomUUID(), name, slug, defaultCommercialModel, settings);
    }

    /**
     * Turns free text into a slug, or fails.
     *
     * <p>Deliberately strict rather than forgiving: the same rule is a CHECK constraint in
     * V6, so anything this accepts and the database rejects would be an insert that fails at
     * the last moment with a constraint name instead of a message. Lowercase alphanumeric
     * words joined by single hyphens, two to sixty-three characters — narrow enough to put
     * in a hostname later without revisiting every stored row.
     */
    public static String requireSlug(String slug) {
        String normalized = slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
        if (!isWellFormedSlug(normalized)) {
            throw new IllegalArgumentException(
                    "tenant slug must be 2-63 characters of lowercase letters, digits and "
                            + "single hyphens, and must not start or end with a hyphen");
        }
        return normalized;
    }

    /**
     * A best-effort slug for a name, or empty when the name yields none.
     *
     * <p>Returns empty rather than inventing something for a name that is entirely
     * punctuation or written in a non-Latin script — Arabic tenant names are expected here,
     * and quietly producing a meaningless slug would be worse than saying so.
     */
    public static Optional<String> slugCandidate(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String candidate = NON_SLUG.matcher(name.toLowerCase(Locale.ROOT))
                .replaceAll("-")
                .replaceAll("^-+|-+$", "");
        if (candidate.length() > 63) {
            candidate = candidate.substring(0, 63).replaceAll("-+$", "");
        }
        return isWellFormedSlug(candidate) ? Optional.of(candidate) : Optional.empty();
    }

    private static boolean isWellFormedSlug(String candidate) {
        return candidate.length() >= 2 && candidate.length() <= 63
                && SLUG.matcher(candidate).matches();
    }

    public void transitionTo(TenantStatus next) {
        TenantStatus current = status();
        if (current == next) {
            return;
        }
        if (!current.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Tenant cannot move from " + current + " to " + next
                            + "; permitted next states are limited by doc 16 section 1");
        }
        this.status = next.code();
    }

    public void rename(String newName) {
        this.name = requireText(newName, "tenant name");
    }

    public void recordActor(UUID actorUserId, boolean creating) {
        if (creating) {
            this.createdByUserId = actorUserId;
        }
        this.updatedByUserId = actorUserId;
    }

    private static String requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        return value.trim();
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String slug() {
        return slug;
    }

    public TenantStatus status() {
        return TenantStatus.fromCode(status);
    }

    public String defaultCommercialModel() {
        return defaultCommercialModel;
    }

    public String settings() {
        return settings;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }

    public UUID createdByUserId() {
        return createdByUserId;
    }

    public UUID updatedByUserId() {
        return updatedByUserId;
    }
}
