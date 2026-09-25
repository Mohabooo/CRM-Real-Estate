package com.rescrm.inventory.repository;

import com.rescrm.platform.money.Money;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.UUID;

import com.rescrm.inventory.domain.Unit;

/**
 * The browse query, as a fragment rather than a {@code @Query} string.
 *
 * <p>It moved because of what the string form costs the planner. Every optional filter was
 * written {@code (:projectId IS NULL OR u.projectId = :projectId)}, and that form cannot be
 * planned generically: with the operand a parameter, PostgreSQL has to re-plan on every
 * execution to discover the OR collapses.
 *
 * <p>Measured against PostgreSQL 16 on 60,000 units across 200 projects. Both forms reach
 * {@code idx_units_project_status} when PostgreSQL is free to build a custom plan — so the
 * index was not going unused, as an earlier version of this comment claimed. The difference
 * appears the moment a generic plan is used:
 *
 * <pre>
 * plan_cache_mode = force_generic_plan
 *   OR form   Seq Scan on units, Rows Removed by Filter: 59,694
 *   this form Index Scan using idx_units_project_status, Index Cond on all three columns
 * </pre>
 *
 * <p>So the OR form buys its index scan by planning the query again every time it runs, and
 * degrades to a sequential scan the moment a cached plan is used instead. Naming only the
 * filters that were supplied is indexable either way.
 *
 * <p>There is a second reason, already paid for once. The string form needed
 * {@code cast(:type as string)} because Hibernate binds an untyped null and PostgreSQL then
 * fails to resolve {@code lower(bytea)} — a bug that reached a build. A predicate that is
 * simply absent cannot have the wrong type.
 *
 * <p>Deal listings will have the same shape, which is why this is corrected before they are
 * written rather than after.
 *
 * <p>Deliberately a fragment with one method, not {@code JpaSpecificationExecutor}. That
 * interface would add {@code findAll(Specification, Pageable)} to the repository — a method
 * whose tenant scoping depends on the caller remembering to put it in the specification.
 * The whole reason these repositories extend the bare {@link org.springframework.data.repository.Repository}
 * marker is that an unscoped read should not be expressible; handing one back through the
 * side door would undo that.
 */
public interface UnitBrowseQuery {

    /**
     * @param tenantId required — there is no unscoped form of this query
     * @param statuses required; the default view is a narrower set, never "everything"
     */
    Page<Unit> browse(UUID tenantId, Collection<String> statuses, UUID projectId, UUID phaseId,
                      String type, Money minPrice, Money maxPrice, BigDecimal minArea,
                      BigDecimal maxArea, Pageable pageable);
}
