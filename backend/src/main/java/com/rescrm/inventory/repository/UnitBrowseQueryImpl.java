package com.rescrm.inventory.repository;

import com.rescrm.inventory.domain.Unit;
import com.rescrm.platform.money.Money;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Builds the browse query from the filters that were actually supplied.
 *
 * <p>Spring Data finds this by name: a fragment interface {@code UnitBrowseQuery} is
 * implemented by {@code UnitBrowseQueryImpl}, and {@code UnitRepository} extending the
 * fragment is what wires them together.
 */
public class UnitBrowseQueryImpl implements UnitBrowseQuery {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<Unit> browse(UUID tenantId, Collection<String> statuses, UUID projectId,
                             UUID phaseId, String type, Money minPrice, Money maxPrice,
                             BigDecimal minArea, BigDecimal maxArea, Pageable pageable) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();

        CriteriaQuery<Unit> query = builder.createQuery(Unit.class);
        Root<Unit> unit = query.from(Unit.class);
        query.where(predicates(builder, unit, tenantId, statuses, projectId, phaseId, type,
                        minPrice, maxPrice, minArea, maxArea)
                .toArray(Predicate[]::new));
        // Ordering is part of the contract, not a detail: an agent reading a price list
        // needs the same unit in the same place every time they look.
        query.orderBy(builder.asc(unit.get("projectId")), builder.asc(unit.get("code")));

        List<Unit> content = entityManager.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        return new PageImpl<>(content, pageable,
                count(builder, tenantId, statuses, projectId, phaseId, type,
                        minPrice, maxPrice, minArea, maxArea));
    }

    private long count(CriteriaBuilder builder, UUID tenantId, Collection<String> statuses,
                       UUID projectId, UUID phaseId, String type, Money minPrice,
                       Money maxPrice, BigDecimal minArea, BigDecimal maxArea) {
        CriteriaQuery<Long> query = builder.createQuery(Long.class);
        Root<Unit> unit = query.from(Unit.class);
        query.select(builder.count(unit));
        query.where(predicates(builder, unit, tenantId, statuses, projectId, phaseId, type,
                        minPrice, maxPrice, minArea, maxArea)
                .toArray(Predicate[]::new));
        return entityManager.createQuery(query).getSingleResult();
    }

    /**
     * One predicate per supplied filter, and none for the others.
     *
     * <p>This is the whole point of the class. An absent filter contributes nothing to the
     * query rather than a parameter-dependent {@code OR}, so the planner sees a plain
     * conjunction over indexed columns and can cache one plan for it.
     */
    private List<Predicate> predicates(CriteriaBuilder builder, Root<Unit> unit, UUID tenantId,
                                       Collection<String> statuses, UUID projectId, UUID phaseId,
                                       String type, Money minPrice, Money maxPrice,
                                       BigDecimal minArea, BigDecimal maxArea) {
        List<Predicate> where = new ArrayList<>();

        // Never optional. The second layer of doc 22 section 4's isolation is this predicate;
        // row-level security is the first, and neither is trusted alone.
        where.add(builder.equal(unit.get("tenantId"), tenantId));
        where.add(unit.get("status").in(statuses));

        if (projectId != null) {
            where.add(builder.equal(unit.get("projectId"), projectId));
        }
        if (phaseId != null) {
            where.add(builder.equal(unit.get("phaseId"), phaseId));
        }
        if (type != null && !type.isBlank()) {
            where.add(builder.equal(builder.lower(unit.get("type")),
                    type.trim().toLowerCase(Locale.ROOT)));
        }
        if (minPrice != null) {
            where.add(builder.greaterThanOrEqualTo(unit.get("listPrice"), minPrice));
        }
        if (maxPrice != null) {
            where.add(builder.lessThanOrEqualTo(unit.get("listPrice"), maxPrice));
        }
        if (minArea != null) {
            where.add(builder.greaterThanOrEqualTo(unit.get("areaSqm"), minArea));
        }
        if (maxArea != null) {
            where.add(builder.lessThanOrEqualTo(unit.get("areaSqm"), maxArea));
        }
        return where;
    }
}
