package com.rescrm.deals.repository;

import com.rescrm.deals.domain.DealDiscount;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DealDiscountRepository extends Repository<DealDiscount, UUID> {

    DealDiscount save(DealDiscount discount);

    @Query("SELECT d FROM DealDiscount d WHERE d.tenantId = :tenantId AND d.dealId = :dealId "
            + "ORDER BY d.createdAt ASC")
    List<DealDiscount> findAllForDeal(@Param("tenantId") UUID tenantId,
                                      @Param("dealId") UUID dealId);

    /**
     * Removes one concession from a draft deal.
     *
     * <p>The only delete in the financial path, and it is bounded to a draft by the service
     * that calls it. A discount on a signed deal is part of the agreed price, and removing
     * it would rewrite what the customer accepted.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM DealDiscount d WHERE d.tenantId = :tenantId AND d.id = :id "
            + "AND d.dealId = :dealId")
    int deleteFromDraft(@Param("tenantId") UUID tenantId, @Param("dealId") UUID dealId,
                        @Param("id") UUID id);
}
