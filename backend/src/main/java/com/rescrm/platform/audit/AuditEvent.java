package com.rescrm.platform.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One immutable entry in the audit trail (doc 28, section 13).
 *
 * <p>There are no setters and no {@code updated_at}. A record that can be amended is not an
 * audit record, and the database enforces the same thing with triggers that reject UPDATE and
 * DELETE outright — so a future mapping mistake cannot quietly open a write path.
 *
 * <p>References other entities by identifier rather than by association. {@code platform} must
 * not depend on any domain module (architecture rule {@code platform_does_not_depend_on_domain}),
 * and an audit row must survive its subject regardless.
 */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** Null only for system-actor transitions (doc 18); never null for a user action. */
    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "entity_type", nullable = false, updatable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false, updatable = false)
    private UUID entityId;

    @Column(name = "action", nullable = false, updatable = false)
    private String action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before", updatable = false)
    private String before;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after", updatable = false)
    private String after;

    @Column(name = "reason", updatable = false)
    private String reason;

    @Column(name = "correlation_id", updatable = false)
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected AuditEvent() {
        // JPA
    }

    AuditEvent(UUID id, UUID tenantId, UUID actorUserId, String entityType, UUID entityId,
               String action, String before, String after, String reason, String correlationId,
               OffsetDateTime createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.actorUserId = actorUserId;
        this.entityType = entityType;
        this.entityId = entityId;
        this.action = action;
        this.before = before;
        this.after = after;
        this.reason = reason;
        this.correlationId = correlationId;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID actorUserId() {
        return actorUserId;
    }

    public String entityType() {
        return entityType;
    }

    public UUID entityId() {
        return entityId;
    }

    public String action() {
        return action;
    }

    public String before() {
        return before;
    }

    public String after() {
        return after;
    }

    public String reason() {
        return reason;
    }

    public String correlationId() {
        return correlationId;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
