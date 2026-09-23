package com.rescrm.inventory.repository;

import com.rescrm.inventory.domain.Unit;
import com.rescrm.platform.money.Money;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UnitRepository extends Repository<Unit, UUID> {

    Unit save(Unit unit);

    /**
     * Writes the row now rather than at commit.
     *
     * <p>Needed wherever a unique constraint is translated into an {@code ApiException}:
     * {@code save} only stages the insert in the persistence context, so the violation would
     * otherwise surface inside {@code JpaTransactionManager.doCommit}, long after the
     * {@code catch} block meant to handle it, and reach the client as a 500.
     */
    Unit saveAndFlush(Unit unit);

    Optional<Unit> findByTenantIdAndId(UUID tenantId, UUID id);

    List<Unit> findAllByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);

    /**
     * E3-S4: browse and filter across projects and both commercial models in one list.
     *
     * <p>Every filter is optional and null means "no filter". {@code statuses} is a required
     * set rather than an optional one, because the default view excludes sold and blocked
     * units and an omitted status filter that quietly meant "everything" would put sold
     * units back in front of agents — the exact mistake the acceptance criterion names.
     */
    @Query("SELECT u FROM Unit u WHERE u.tenantId = :tenantId "
            + "AND u.status IN :statuses "
            + "AND (:projectId IS NULL OR u.projectId = :projectId) "
            + "AND (:phaseId IS NULL OR u.phaseId = :phaseId) "
            // cast(:type as string) is load-bearing, not decoration. With a bare :type
            // Hibernate has nothing to infer the parameter's SQL type from when the value
            // is null, binds it as untyped, and PostgreSQL fails to resolve lower(bytea).
            // The cast states the type, so the null branch is a plain text comparison.
            // The value arrives already lower-cased from UnitService.browse.
            + "AND (cast(:type as string) IS NULL OR lower(u.type) = cast(:type as string)) "
            + "AND (:minPrice IS NULL OR u.listPrice >= :minPrice) "
            + "AND (:maxPrice IS NULL OR u.listPrice <= :maxPrice) "
            + "AND (:minArea IS NULL OR u.areaSqm >= :minArea) "
            + "AND (:maxArea IS NULL OR u.areaSqm <= :maxArea) "
            + "ORDER BY u.projectId ASC, u.code ASC")
    Page<Unit> findFiltered(@Param("tenantId") UUID tenantId,
                            @Param("statuses") Collection<String> statuses,
                            @Param("projectId") UUID projectId,
                            @Param("phaseId") UUID phaseId,
                            @Param("type") String type,
                            @Param("minPrice") Money minPrice,
                            @Param("maxPrice") Money maxPrice,
                            @Param("minArea") BigDecimal minArea,
                            @Param("maxArea") BigDecimal maxArea,
                            Pageable pageable);

    /**
     * The double-sell guard (E3-S5), expressed as a single conditional statement.
     *
     * <p>This is the whole mechanism, and it is worth being precise about why it works. The
     * {@code WHERE} clause names the status the caller believes the unit to be in. Under
     * PostgreSQL's read-committed isolation, two concurrent transactions running this
     * statement serialise on the row: the second blocks until the first commits, then
     * re-evaluates the predicate against the committed row, finds the status has moved, and
     * matches nothing. So the return value is the answer — 1 means this caller took the unit,
     * 0 means somebody else did — and no amount of application-level checking beforehand is
     * needed or would help.
     *
     * <p>A read-then-write in the service would be the bug this avoids: the gap between the
     * read and the write is exactly where the second claim slips through.
     *
     * <p>Doc 22's C1 and C2 — one active deal and one active reservation per unit — are
     * partial unique indexes on tables that arrive in Epics 4 and 5. They will make the same
     * guarantee from the other direction. Both are wanted; neither alone is the belt and
     * braces doc 28 asks for.
     *
     * <p>Used only for the contested transitions — into {@code reserved} and {@code sold} —
     * where two callers may be racing. Blocking and unblocking go through the entity instead,
     * because they also move {@code blocked_reason}, and a bulk update that changed the status
     * without the reason would leave a row explaining a block that had ended.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Unit u SET u.status = :toStatus, u.updatedByUserId = :actorUserId "
            + "WHERE u.id = :unitId AND u.tenantId = :tenantId AND u.status = :expectedStatus")
    int transitionIfInStatus(@Param("tenantId") UUID tenantId,
                             @Param("unitId") UUID unitId,
                             @Param("expectedStatus") String expectedStatus,
                             @Param("toStatus") String toStatus,
                             @Param("actorUserId") UUID actorUserId);

    Optional<Unit> findByTenantIdAndProjectIdAndCodeIgnoreCase(UUID tenantId, UUID projectId,
                                                               String code);

    long countByTenantIdAndProjectIdAndStatus(UUID tenantId, UUID projectId, String status);

    long countByTenantIdAndProjectId(UUID tenantId, UUID projectId);

    long countByTenantId(UUID tenantId);
}
