package com.rescrm.deals.repository;

import com.rescrm.deals.domain.Installment;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Expected obligations. Nothing here reads or writes an allocation, because no such column
 * exists until Epic 6.
 */
public interface InstallmentRepository extends Repository<Installment, UUID> {

    Installment save(Installment installment);

    @Query("SELECT i FROM Installment i WHERE i.tenantId = :tenantId AND i.planId = :planId "
            + "ORDER BY i.sequenceNo ASC")
    List<Installment> findAllForPlan(@Param("tenantId") UUID tenantId,
                                     @Param("planId") UUID planId);

    @Query("SELECT i FROM Installment i WHERE i.tenantId = :tenantId AND i.dealId = :dealId "
            + "AND i.status <> 'void' ORDER BY i.sequenceNo ASC")
    List<Installment> findLiveForDeal(@Param("tenantId") UUID tenantId,
                                      @Param("dealId") UUID dealId);

    /**
     * Discards a draft plan's rows so it can be regenerated (TPL-006).
     *
     * <p>Bounded to a draft plan by the service. Rows on an active plan are what a customer
     * agreed to pay; they are voided with a reason, never deleted.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Installment i WHERE i.tenantId = :tenantId AND i.planId = :planId")
    int deleteDraftRows(@Param("tenantId") UUID tenantId, @Param("planId") UUID planId);

    long countByTenantId(UUID tenantId);
}
