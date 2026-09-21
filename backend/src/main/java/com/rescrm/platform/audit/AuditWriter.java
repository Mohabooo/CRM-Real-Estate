package com.rescrm.platform.audit;

import com.rescrm.platform.observability.CorrelationId;
import com.rescrm.platform.security.SecurityContext;
import com.rescrm.platform.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Writes audit entries. The actor, tenant and correlation id come from the request context
 * rather than from arguments, so a caller cannot record an action against the wrong person.
 *
 * <p>Joins the caller's transaction deliberately ({@code MANDATORY} is not used only because
 * provisioning writes the tenant and its first audit row together). If the business change
 * rolls back, its audit entry rolls back with it — an audit trail describing changes that
 * did not happen is worse than none, because it is trusted.
 */
@Service
public class AuditWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditWriter.class);

    private final AuditEventRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuditWriter(AuditEventRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public AuditEvent record(AuditAction action, String entityType, UUID entityId,
                             Map<String, Object> before, Map<String, Object> after,
                             String reason) {
        UUID tenantId = TenantContext.require();
        UUID actorUserId = SecurityContext.current()
                .map(principal -> principal.userId())
                .orElse(null);

        AuditEvent event = new AuditEvent(
                UUID.randomUUID(),
                tenantId,
                actorUserId,
                entityType,
                entityId,
                action.name(),
                toJson(before),
                toJson(after),
                reason,
                CorrelationId.current(),
                OffsetDateTime.now(clock));

        return repository.save(event);
    }

    /** Convenience for creations, where there is no prior state to record. */
    @Transactional(propagation = Propagation.REQUIRED)
    public AuditEvent recordCreation(AuditAction action, String entityType, UUID entityId,
                                     Map<String, Object> after) {
        return record(action, entityType, entityId, null, after, null);
    }

    private String toJson(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // Never fail the business transaction because a diff would not serialize. Record
            // the failure and keep the entry: an audit row with a missing diff is still
            // evidence that the action happened, and losing the action is the worse outcome.
            log.warn("Audit payload could not be serialized; entry written without it", e);
            return null;
        }
    }
}
