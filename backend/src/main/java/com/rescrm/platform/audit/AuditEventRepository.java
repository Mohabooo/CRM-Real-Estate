package com.rescrm.platform.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Read and append only.
 *
 * <p>No delete or update method is exposed, and the inherited ones are overridden to refuse.
 * The database triggers already reject those operations; this makes the same rule visible at
 * the call site instead of surfacing as a runtime error from the driver.
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    @Query("SELECT e FROM AuditEvent e WHERE e.tenantId = :tenantId "
            + "AND e.entityType = :entityType AND e.entityId = :entityId "
            + "ORDER BY e.createdAt DESC")
    List<AuditEvent> findTrail(@Param("tenantId") UUID tenantId,
                               @Param("entityType") String entityType,
                               @Param("entityId") UUID entityId);

    @Query("SELECT e FROM AuditEvent e WHERE e.tenantId = :tenantId ORDER BY e.createdAt DESC")
    List<AuditEvent> findForTenant(@Param("tenantId") UUID tenantId);
}
