package com.rescrm.reservations.api;

import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.money.CurrencyCode;
import com.rescrm.platform.money.Money;
import com.rescrm.reservations.api.ReservationDtos.CancelReservationRequest;
import com.rescrm.reservations.api.ReservationDtos.ExtendReservationRequest;
import com.rescrm.reservations.api.ReservationDtos.PageResponse;
import com.rescrm.reservations.api.ReservationDtos.PlaceReservationRequest;
import com.rescrm.reservations.api.ReservationDtos.RecordDepositRequest;
import com.rescrm.reservations.api.ReservationDtos.ReleaseReservationRequest;
import com.rescrm.reservations.api.ReservationDtos.ReservationResponse;
import com.rescrm.reservations.domain.Reservation;
import com.rescrm.reservations.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Doc 23's {@code /reservations} (Epic 4).
 *
 * <p>{@code POST /reservations/{id}/convert} from doc 23's map is absent. It is specified to
 * create a draft deal and return it, and deals are Epic 5 — an endpoint that marked a hold
 * converted without producing the deal would leave the system claiming a sale that does not
 * exist. The state machine already allows the transition; the epic that can honour it will
 * add the endpoint.
 *
 * <p>Placing a hold on a unit somebody else already holds answers 409 with the current
 * holder's identifier, which is what doc 23 specifies (F2/A3).
 */
@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationController {

    private static final int MAX_PAGE_SIZE = 200;

    private final ReservationService reservations;

    public ReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    /** Already narrowed to the caller's scope; there is no widening parameter. */
    @GetMapping
    public PageResponse<ReservationResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int bounded = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageResponse.of(
                reservations.listVisibleToCaller(PageRequest.of(Math.max(page, 0), bounded)),
                this::toResponse);
    }

    @GetMapping("/{id}")
    public ReservationResponse get(@PathVariable UUID id) {
        return toResponse(reservations.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse place(@Valid @RequestBody PlaceReservationRequest request) {
        return toResponse(reservations.place(request.unitId(), request.leadId(),
                request.customerId(), request.expiresAt(),
                parseAmountOrNull(request.depositAmount()), request.depositReceived()));
    }

    @PostMapping("/{id}/confirm")
    public ReservationResponse confirm(@PathVariable UUID id) {
        return toResponse(reservations.confirm(id));
    }

    @PostMapping("/{id}/release")
    public ReservationResponse release(@PathVariable UUID id,
                                       @Valid @RequestBody ReleaseReservationRequest request) {
        return toResponse(reservations.release(id, request.reason()));
    }

    @PostMapping("/{id}/cancel")
    public ReservationResponse cancel(@PathVariable UUID id,
                                      @RequestBody(required = false)
                                      CancelReservationRequest request) {
        return toResponse(reservations.cancel(id, request == null ? null : request.reason()));
    }

    @PostMapping("/{id}/extend")
    public ReservationResponse extend(@PathVariable UUID id,
                                      @Valid @RequestBody ExtendReservationRequest request) {
        return toResponse(reservations.extend(id, request.expiresAt(), request.reason()));
    }

    @PostMapping("/{id}/deposit")
    public ReservationResponse recordDeposit(@PathVariable UUID id,
                                             @Valid @RequestBody RecordDepositRequest request) {
        return toResponse(reservations.recordDeposit(id, parseAmountOrNull(request.amount()),
                request.received()));
    }

    private ReservationResponse toResponse(Reservation reservation) {
        return ReservationResponse.from(reservation,
                reservations.depositMeaningFor(reservation.id()).name());
    }

    /**
     * Amounts arrive as decimal strings, never JSON numbers (doc 22 section 8), and the
     * translation from "that is not a valid amount" into a 400 happens here rather than
     * surfacing {@code Money}'s refusal as a 500.
     */
    private static Money parseAmountOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Money.of(new BigDecimal(raw.trim()), CurrencyCode.EGP);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "'" + raw + "' is not a valid deposit amount");
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
    }
}
