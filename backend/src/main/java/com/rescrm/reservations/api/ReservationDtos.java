package com.rescrm.reservations.api;

import com.rescrm.reservations.domain.Reservation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Request and response shapes for {@code /reservations}.
 *
 * <p>The deposit crosses the wire as a decimal string and is accompanied by
 * {@code depositMeaning}, which is the commercial model's answer rather than the client's
 * business to work out. A screen showing "EGP 50,000 received" must be able to say whether
 * that is money the tenant holds or a confirmation the developer collected it, and doc 25
 * is clear the difference is invisible in the number.
 */
public final class ReservationDtos {

    private ReservationDtos() {
    }

    public record ReservationResponse(UUID id, UUID unitId, UUID leadId, UUID customerId,
                                      UUID agentUserId, UUID branchId,
                                      OffsetDateTime reservedAt, OffsetDateTime expiresAt,
                                      OffsetDateTime originalExpiresAt, int extensionCount,
                                      String depositAmount, boolean depositReceived,
                                      String depositMeaning, String status,
                                      String closedReason, OffsetDateTime closedAt) {

        public static ReservationResponse from(Reservation reservation, String depositMeaning) {
            return new ReservationResponse(reservation.id(), reservation.unitId(),
                    reservation.leadId(), reservation.customerId(), reservation.agentUserId(),
                    reservation.branchId(), reservation.reservedAt(), reservation.expiresAt(),
                    reservation.originalExpiresAt(), reservation.extensionCount(),
                    reservation.depositAmount() == null
                            ? null : reservation.depositAmount().toPlainString(),
                    reservation.isDepositReceived(), depositMeaning,
                    reservation.status().code(), reservation.closedReason(),
                    reservation.closedAt());
        }
    }

    /**
     * {@code expiresAt} is optional and defaults from tenant settings (E4-S1), so the common
     * case is one fewer decision for an agent with a customer in front of them.
     */
    public record PlaceReservationRequest(@NotNull UUID unitId,
                                          UUID leadId,
                                          UUID customerId,
                                          OffsetDateTime expiresAt,
                                          String depositAmount,
                                          boolean depositReceived) {
    }

    public record ReleaseReservationRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record CancelReservationRequest(@Size(max = 500) String reason) {
    }

    public record ExtendReservationRequest(@NotNull OffsetDateTime expiresAt,
                                           @Size(max = 500) String reason) {
    }

    public record RecordDepositRequest(String amount, boolean received) {
    }

    public record PageResponse<T>(List<T> items, int page, int size, long totalElements,
                                  int totalPages) {

        public static <E, T> PageResponse<T> of(Page<E> source,
                                                java.util.function.Function<E, T> mapper) {
            return new PageResponse<>(source.getContent().stream().map(mapper).toList(),
                    source.getNumber(), source.getSize(), source.getTotalElements(),
                    source.getTotalPages());
        }
    }
}
