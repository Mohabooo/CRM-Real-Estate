package com.rescrm.inventory.api;

import com.rescrm.inventory.api.InventoryDtos.BlockUnitRequest;
import com.rescrm.inventory.api.InventoryDtos.CreateUnitRequest;
import com.rescrm.inventory.api.InventoryDtos.PageResponse;
import com.rescrm.inventory.api.InventoryDtos.UnitClaimResponse;
import com.rescrm.inventory.api.InventoryDtos.UnitHistoryEntry;
import com.rescrm.inventory.api.InventoryDtos.UnitResponse;
import com.rescrm.inventory.api.InventoryDtos.UpdateUnitRequest;
import com.rescrm.inventory.domain.UnitStatus;
import com.rescrm.inventory.service.UnitClaim;
import com.rescrm.inventory.service.UnitService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Doc 23's {@code /units} (E3-S4, E3-S5).
 *
 * <p>{@code PATCH /units/{id}} covers list price and attributes and rejects any attempt to
 * write status — which here means the request record has no status field at all, so an
 * attempt is not rejected so much as impossible to express. Status moves through
 * {@code /block}, {@code /unblock} and the claim endpoints, each of which knows the single
 * status it is allowed to produce.
 */
@RestController
@RequestMapping("/api/v1/units")
public class UnitController {

    private static final int MAX_PAGE_SIZE = 200;

    private final UnitService units;

    public UnitController(UnitService units) {
        this.units = units;
    }

    // ------------------------------------------------------------------ browse

    /**
     * E3-S4: one list across every project and both commercial models.
     *
     * <p>Omitting {@code status} gives the default view, which excludes sold and blocked
     * units. Seeing them takes naming them — there is no {@code all=true} — because the whole
     * value of the default is that an agent never quotes a unit that is already gone.
     */
    @GetMapping
    public PageResponse<UnitResponse> browse(
            @RequestParam(required = false) List<UnitStatus> status,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID phaseId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) BigDecimal minArea,
            @RequestParam(required = false) BigDecimal maxArea,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int bounded = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageResponse.of(units.browse(status, projectId, phaseId, type,
                        UnitPrices.parseOrNull(minPrice), UnitPrices.parseOrNull(maxPrice),
                        minArea, maxArea, PageRequest.of(Math.max(page, 0), bounded)),
                UnitResponse::from);
    }

    @GetMapping("/{id}")
    public UnitResponse get(@PathVariable UUID id) {
        return UnitResponse.from(units.get(id));
    }

    /** Doc 23's {@code GET /units/{id}/history}, served from the audit trail. */
    @GetMapping("/{id}/history")
    public List<UnitHistoryEntry> history(@PathVariable UUID id) {
        return units.history(id).stream().map(UnitHistoryEntry::from).toList();
    }

    // ---------------------------------------------------------------- mutation

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UnitResponse create(@Valid @RequestBody CreateUnitRequest request) {
        return UnitResponse.from(units.create(request.projectId(), request.phaseId(),
                request.code(), request.type(), request.areaSqm(), request.floor(),
                request.view(), UnitPrices.require(request.listPrice(), "listPrice")));
    }

    @PatchMapping("/{id}")
    public UnitResponse update(@PathVariable UUID id,
                               @Valid @RequestBody UpdateUnitRequest request) {
        return UnitResponse.from(units.updateAttributes(id, request.type(), request.areaSqm(),
                request.floor(), request.view(), UnitPrices.parseOrNull(request.listPrice()),
                request.phaseId()));
    }

    @PostMapping("/{id}/block")
    public UnitResponse block(@PathVariable UUID id,
                              @Valid @RequestBody BlockUnitRequest request) {
        return UnitResponse.from(units.block(id, request.reason()));
    }

    @PostMapping("/{id}/unblock")
    public UnitResponse unblock(@PathVariable UUID id) {
        return UnitResponse.from(units.unblock(id));
    }

    // ------------------------------------------------------------------ claims

    /**
     * E3-S5, over HTTP. Losing the race is a 409 carrying the unit's current status, which is
     * what doc 23 specifies for a unit that is already held — not a 500, and not a 200 with a
     * quiet {@code claimed: false} that a careless client would treat as success.
     *
     * <p>These endpoints exist now so the guard is reachable and testable in Epic 3. Once
     * reservations and deals arrive they will claim units through the service directly, inside
     * their own transactions, and these will likely become internal.
     */
    @PostMapping("/{id}/claim-for-reservation")
    public ResponseEntity<UnitClaimResponse> claimForReservation(@PathVariable UUID id) {
        return respond(units.claimForReservation(id));
    }

    @PostMapping("/{id}/claim-for-sale")
    public ResponseEntity<UnitClaimResponse> claimForSale(@PathVariable UUID id) {
        return respond(units.claimForSale(id));
    }

    private static ResponseEntity<UnitClaimResponse> respond(UnitClaim claim) {
        UnitClaimResponse body = UnitClaimResponse.from(claim);
        return claim.won()
                ? ResponseEntity.ok(body)
                : ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}
