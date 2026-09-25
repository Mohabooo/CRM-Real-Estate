package com.rescrm.deals.repository;

import com.rescrm.deals.domain.PaymentPlanTemplate;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentPlanTemplateRepository extends Repository<PaymentPlanTemplate, UUID> {

    PaymentPlanTemplate save(PaymentPlanTemplate template);

    PaymentPlanTemplate saveAndFlush(PaymentPlanTemplate template);

    Optional<PaymentPlanTemplate> findByTenantIdAndId(UUID tenantId, UUID id);

    /**
     * Templates offered for a project: the ones scoped to it, and the tenant-wide ones.
     *
     * <p>Archived templates are excluded, so a plan cannot be started from one that was
     * retired — while instances already made from it carry on untouched (TPL-002).
     */
    @Query("SELECT t FROM PaymentPlanTemplate t WHERE t.tenantId = :tenantId "
            + "AND t.active = true "
            + "AND (t.projectId IS NULL OR t.projectId = :projectId) "
            + "ORDER BY t.name ASC")
    List<PaymentPlanTemplate> findOfferedFor(@Param("tenantId") UUID tenantId,
                                             @Param("projectId") UUID projectId);

    @Query("SELECT t FROM PaymentPlanTemplate t WHERE t.tenantId = :tenantId "
            + "ORDER BY t.name ASC")
    List<PaymentPlanTemplate> findAllForTenant(@Param("tenantId") UUID tenantId);
}
