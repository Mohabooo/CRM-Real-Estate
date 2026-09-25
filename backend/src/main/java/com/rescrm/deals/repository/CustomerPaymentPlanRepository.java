package com.rescrm.deals.repository;

import com.rescrm.deals.domain.CustomerPaymentPlan;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerPaymentPlanRepository extends Repository<CustomerPaymentPlan, UUID> {

    CustomerPaymentPlan save(CustomerPaymentPlan plan);

    CustomerPaymentPlan saveAndFlush(CustomerPaymentPlan plan);

    Optional<CustomerPaymentPlan> findByTenantIdAndId(UUID tenantId, UUID id);

    /** The draft plan on a deal, if one has been generated. A partial index allows one. */
    @Query("SELECT p FROM CustomerPaymentPlan p WHERE p.tenantId = :tenantId "
            + "AND p.dealId = :dealId AND p.status = 'draft'")
    Optional<CustomerPaymentPlan> findDraftForDeal(@Param("tenantId") UUID tenantId,
                                                   @Param("dealId") UUID dealId);

    /** The live plan on a deal. C3 allows one. */
    @Query("SELECT p FROM CustomerPaymentPlan p WHERE p.tenantId = :tenantId "
            + "AND p.dealId = :dealId AND p.status = 'active'")
    Optional<CustomerPaymentPlan> findActiveForDeal(@Param("tenantId") UUID tenantId,
                                                    @Param("dealId") UUID dealId);

    @Query("SELECT p FROM CustomerPaymentPlan p WHERE p.tenantId = :tenantId "
            + "AND p.dealId = :dealId ORDER BY p.version ASC")
    List<CustomerPaymentPlan> findAllForDeal(@Param("tenantId") UUID tenantId,
                                             @Param("dealId") UUID dealId);

    long countByTenantIdAndSourceTemplateId(UUID tenantId, UUID sourceTemplateId);
}
