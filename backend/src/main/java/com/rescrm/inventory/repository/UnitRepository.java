package com.rescrm.inventory.repository;

import com.rescrm.inventory.domain.Unit;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UnitRepository extends Repository<Unit, UUID>, UnitBrowseQuery {

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
     * E3-S4's browse lives in {@link UnitBrowseQuery}, built from the filters supplied.
     *
     * <p>It used to be a {@code @Query} string in which every optional filter read
     * {@code (:projectId IS NULL OR u.projectId = :projectId)}. That form can only reach
     * doc 22 section 6's inventory indexes by re-planning on every execution, and falls back
     * to a sequential scan under a cached generic plan. The fragment's comment has the
     * measurements.
     */

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
