package com.rescrm.crm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * One recorded contact with a lead or customer (E2-S3).
 *
 * <p>Immutable once written. An activity is a statement that something happened at a time;
 * editing it afterwards turns the timeline into an account of what someone later wished had
 * happened, which is worth less than nothing when a commission is disputed.
 */
@Entity
@Table(name = "activities")
public class Activity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "subject_type", nullable = false, updatable = false)
    private String subjectType;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Column(name = "type", nullable = false, updatable = false)
    private String type;

    @Column(name = "body", updatable = false)
    private String body;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

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

    protected Activity() {
        // JPA
    }

    private Activity(UUID tenantId, ActivitySubject subject, UUID subjectId, ActivityType type,
                     String body, OffsetDateTime occurredAt, UUID userId) {
        this.id = UUID.randomUUID();
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.subjectType = Objects.requireNonNull(subject, "subject").code();
        this.subjectId = Objects.requireNonNull(subjectId, "subjectId");
        this.type = Objects.requireNonNull(type, "type").code();
        this.body = body == null || body.isBlank() ? null : body.trim();
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.userId = userId;
    }

    public static Activity log(UUID tenantId, ActivitySubject subject, UUID subjectId,
                               ActivityType type, String body, OffsetDateTime occurredAt,
                               UUID userId) {
        return new Activity(tenantId, subject, subjectId, type, body, occurredAt, userId);
    }

    public void recordActor(UUID actorUserId) {
        this.createdByUserId = actorUserId;
        this.updatedByUserId = actorUserId;
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public ActivitySubject subject() { return ActivitySubject.fromCode(subjectType); }
    public UUID subjectId() { return subjectId; }
    public ActivityType type() { return ActivityType.fromCode(type); }
    public String body() { return body; }
    public OffsetDateTime occurredAt() { return occurredAt; }
    public UUID userId() { return userId; }
    public OffsetDateTime createdAt() { return createdAt; }
}
