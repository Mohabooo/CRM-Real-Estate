package com.rescrm.platform.audit;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Read and append only.
 *
 * <p>Extends the bare {@link Repository} marker rather than {@code JpaRepository}, so
 * {@code delete}, {@code deleteById} and {@code deleteAll} do not exist on this type at all.
 * The database triggers refuse those operations anyway, but an interface that offers them is
 * an interface someone will call: this makes the rule a compile error rather than a runtime
 * one, and {@code AuditTrailIT} asserts the absence so it cannot be reintroduced.
 */
public interface AuditEventRepository extends Repository<AuditEvent, UUID> {

    AuditEvent save(AuditEvent event);

    @Query("SELECT e FROM AuditEvent e WHERE e.tenantId = :tenantId "
            + "AND e.entityType = :entityType AND e.entityId = :entityId "
            + "ORDER BY e.createdAt DESC")
    List<AuditEvent> findTrail(@Param("tenantId") UUID tenantId,
                               @Param("entityType") String entityType,
                               @Param("entityId") UUID entityId);

    @Query("SELECT e FROM AuditEvent e WHERE e.tenantId = :tenantId ORDER BY e.createdAt DESC")
    List<AuditEvent> findForTenant(@Param("tenantId") UUID tenantId);
}
