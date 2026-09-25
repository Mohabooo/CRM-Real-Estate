package com.rescrm.deals.repository;

import com.rescrm.deals.domain.Deal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped throughout; the bare marker keeps an unscoped read inexpressible. */
public interface DealRepository extends Repository<Deal, UUID> {

    Deal save(Deal deal);

    /**
     * Writes now rather than at commit, so C1's unique index surfaces as a catchable
     * exception inside the service rather than inside the transaction manager.
     */
    Deal saveAndFlush(Deal deal);

    Optional<Deal> findByTenantIdAndId(UUID tenantId, UUID id);

    Page<Deal> findAllByTenantIdOrderByDealDateDesc(UUID tenantId, Pageable pageable);

    Page<Deal> findAllByTenantIdAndBranchIdOrderByDealDateDesc(UUID tenantId, UUID branchId,
                                                               Pageable pageable);

    Page<Deal> findAllByTenantIdAndAgentUserIdOrderByDealDateDesc(UUID tenantId, UUID agentUserId,
                                                                  Pageable pageable);

    /** The live deal on a unit, if any. C1 guarantees there is at most one. */
    @Query("SELECT d FROM Deal d WHERE d.tenantId = :tenantId AND d.unitId = :unitId "
            + "AND d.status IN ('draft', 'active')")
    Optional<Deal> findLiveForUnit(@Param("tenantId") UUID tenantId,
                                   @Param("unitId") UUID unitId);

    long countByTenantId(UUID tenantId);
}
