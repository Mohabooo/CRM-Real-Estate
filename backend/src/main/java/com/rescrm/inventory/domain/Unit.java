package com.rescrm.inventory.domain;

import com.rescrm.platform.money.Money;
import com.rescrm.platform.money.persistence.MoneyAmountConverter;
import com.rescrm.platform.money.persistence.MoneyColumns;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * The atomic sellable item (doc 16, section 9).
 *
 * <p>Two things about this class carry most of Epic 3's weight.
 *
 * <p>The list price is {@link Money}, not {@code BigDecimal} and emphatically not a double.
 * It becomes a deal's gross value, which becomes a payment schedule, which becomes a
 * commission — doc 28 decision A17 exists because an error introduced here is invisible until
 * it appears in somebody's commission statement.
 *
 * <p>The status has no public setter. Doc 23: "Unit status is never set directly by a client
 * — it is a consequence of reservation and deal actions." The transitions below are named
 * after the event that causes them rather than the state they produce, so a caller has to
 * have a reason rather than merely a value. The database enforces the same vocabulary
 * independently, and {@code UnitService} adds the conditional update that makes a concurrent
 * double-claim impossible (E3-S5).
 */
@Entity
@Table(name = "units")
public class Unit {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "phase_id")
    private UUID phaseId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "type")
    private String type;

    @Column(name = "area_sqm")
    private BigDecimal areaSqm;

    @Column(name = "floor")
    private String floor;

    @Column(name = "view")
    private String view;

    /**
     * {@code columnDefinition} is required, not decorative: the column is the
     * {@code money_amount} domain, and JDBC reports a domain as {@code Types.DISTINCT}
     * rather than {@code Types.NUMERIC}. Without the declaration Hibernate's schema
     * validation rejects the mapping at startup. See {@link MoneyColumns}.
     */
    @Convert(converter = MoneyAmountConverter.class)
    @Column(name = "list_price", nullable = false, columnDefinition = MoneyColumns.AMOUNT)
    private Money listPrice;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "blocked_reason")
    private String blockedReason;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @Column(name = "updated_by_user_id")
    private UUID updatedByUserId;

    protected Unit() {
        // JPA
    }

    private Unit(UUID tenantId, UUID projectId, UUID phaseId, String code, String type,
                 BigDecimal areaSqm, String floor, String view, Money listPrice) {
        this.id = UUID.randomUUID();
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        this.phaseId = phaseId;
        this.code = requireText(code, "unit code");
        this.type = blankToNull(type);
        this.areaSqm = requirePositiveOrNull(areaSqm);
        this.floor = blankToNull(floor);
        this.view = blankToNull(view);
        this.listPrice = requirePositivePrice(listPrice);
        this.status = UnitStatus.AVAILABLE.code();
    }

    /**
     * Doc 18 section 2: a unit enters inventory belonging to a project and carrying a list
     * price. Both preconditions are here rather than in the service, because a unit without
     * a price is not a unit anyone can sell.
     */
    public static Unit create(UUID tenantId, UUID projectId, UUID phaseId, String code,
                              String type, BigDecimal areaSqm, String floor, String view,
                              Money listPrice) {
        return new Unit(tenantId, projectId, phaseId, code, type, areaSqm, floor, view,
                listPrice);
    }

    // ------------------------------------------------------------------ attributes

    /**
     * Doc 23: {@code PATCH /units/{id}} covers list price and attributes, never status.
     * There is no status parameter here to reject — it simply is not an argument.
     */
    public void updateAttributes(String newType, BigDecimal newAreaSqm, String newFloor,
                                 String newView, Money newListPrice, UUID newPhaseId) {
        BigDecimal candidateArea = newAreaSqm != null ? requirePositiveOrNull(newAreaSqm)
                : this.areaSqm;
        Money candidatePrice = newListPrice != null ? requirePositivePrice(newListPrice)
                : this.listPrice;

        this.areaSqm = candidateArea;
        this.listPrice = candidatePrice;
        if (newType != null) {
            this.type = blankToNull(newType);
        }
        if (newFloor != null) {
            this.floor = blankToNull(newFloor);
        }
        if (newView != null) {
            this.view = blankToNull(newView);
        }
        if (newPhaseId != null) {
            this.phaseId = newPhaseId;
        }
    }

    // ------------------------------------------------------------------ transitions

    /** Withheld from inventory by operations. Doc 18 section 2: a reason is required. */
    public void block(String reason) {
        String trimmed = requireText(reason, "block reason");
        requireTransition(UnitStatus.BLOCKED);
        this.status = UnitStatus.BLOCKED.code();
        this.blockedReason = trimmed;
    }

    public void unblock() {
        if (status() != UnitStatus.BLOCKED) {
            throw new IllegalStateException("Only a blocked unit can be unblocked; this one is "
                    + status().code());
        }
        this.status = UnitStatus.AVAILABLE.code();
        this.blockedReason = null;
    }

    /**
     * Held by a reservation. Called by the reservations epic; the guard against two
     * simultaneous holds is the conditional update in {@code UnitService}, not this method.
     */
    public void markReserved() {
        requireTransition(UnitStatus.RESERVED);
        this.status = UnitStatus.RESERVED.code();
    }

    /** Left sellable inventory on deal activation. */
    public void markSold() {
        requireTransition(UnitStatus.SOLD);
        this.status = UnitStatus.SOLD.code();
    }

    /** Returned to inventory on release, expiry or cancellation. */
    public void returnToInventory() {
        requireTransition(UnitStatus.AVAILABLE);
        this.status = UnitStatus.AVAILABLE.code();
        this.blockedReason = null;
    }

    public void recordActor(UUID actorUserId, boolean creating) {
        if (creating) {
            this.createdByUserId = actorUserId;
        }
        this.updatedByUserId = actorUserId;
    }

    // ------------------------------------------------------------------ internals

    private void requireTransition(UnitStatus next) {
        UnitStatus current = status();
        if (!current.canTransitionTo(next)) {
            throw new IllegalStateException("A unit cannot move from " + current.code() + " to "
                    + next.code() + " (doc 18 section 2)");
        }
    }

    private static Money requirePositivePrice(Money price) {
        if (price == null) {
            throw new IllegalArgumentException("a unit needs a list price");
        }
        if (!price.isPositive()) {
            throw new IllegalArgumentException("a unit's list price must be greater than zero");
        }
        return price;
    }

    private static BigDecimal requirePositiveOrNull(BigDecimal area) {
        if (area == null) {
            return null;
        }
        if (area.signum() <= 0) {
            throw new IllegalArgumentException("area must be greater than zero");
        }
        return area;
    }

    private static String requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public UUID projectId() { return projectId; }
    public UUID phaseId() { return phaseId; }
    public String code() { return code; }
    public String type() { return type; }
    public BigDecimal areaSqm() { return areaSqm; }
    public String floor() { return floor; }
    public String view() { return view; }
    public Money listPrice() { return listPrice; }
    public UnitStatus status() { return UnitStatus.fromCode(status); }
    public String blockedReason() { return blockedReason; }
    public OffsetDateTime createdAt() { return createdAt; }
    public OffsetDateTime updatedAt() { return updatedAt; }
    public UUID createdByUserId() { return createdByUserId; }
    public UUID updatedByUserId() { return updatedByUserId; }
}
