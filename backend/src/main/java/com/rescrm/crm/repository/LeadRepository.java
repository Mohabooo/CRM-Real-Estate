package com.rescrm.crm.repository;

import com.rescrm.crm.domain.Lead;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped throughout, and extends the bare {@link Repository} marker so no unscoped
 * finder exists to call by accident — the same rule the identity module follows.
 */
public interface LeadRepository extends Repository<Lead, UUID> {

    Lead save(Lead lead);

    Optional<Lead> findByTenantIdAndId(UUID tenantId, UUID id);

    Page<Lead> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    Page<Lead> findAllByTenantIdAndBranchIdOrderByCreatedAtDesc(UUID tenantId, UUID branchId,
                                                                Pageable pageable);

    Page<Lead> findAllByTenantIdAndOwnerUserIdOrderByCreatedAtDesc(UUID tenantId, UUID ownerUserId,
                                                                   Pageable pageable);

    /** Duplicate detection (E2-S1). Matches are warnings, so this returns them all. */
    List<Lead> findAllByTenantIdAndPhoneNormalized(UUID tenantId, String phoneNormalized);

    List<Lead> findAllByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);

    /**
     * The stale queue (E2-S3): active leads whose next action is in the past.
     *
     * <p>Derived on read rather than stored. A nightly job flipping leads to "stale" would be
     * one failed run away from a queue that silently under-reports, which is the argument
     * doc 18 makes for not storing derived states.
     */
    @Query("SELECT l FROM Lead l WHERE l.tenantId = :tenantId AND l.status = 'active' "
            + "AND l.nextActionAt IS NOT NULL AND l.nextActionAt < :asOf "
            + "AND (:branchId IS NULL OR l.branchId = :branchId) "
            + "ORDER BY l.nextActionAt ASC")
    List<Lead> findStale(@Param("tenantId") UUID tenantId,
                         @Param("asOf") OffsetDateTime asOf,
                         @Param("branchId") UUID branchId);

    long countByTenantId(UUID tenantId);
}
