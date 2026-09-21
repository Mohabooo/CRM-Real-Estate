package com.rescrm.crm.api;

import com.rescrm.crm.domain.Activity;
import com.rescrm.crm.domain.ActivitySubject;
import com.rescrm.crm.domain.ActivityType;
import com.rescrm.crm.domain.Customer;
import com.rescrm.crm.domain.Lead;
import com.rescrm.crm.service.DuplicateMatch;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Request and response shapes for the CRM endpoints (doc 23's {@code /leads},
 * {@code /customers} and {@code /activities}).
 *
 * <p>Two fields never appear in any response: a lead's or customer's {@code tenant_id}, because
 * a client that can see it starts believing it can send it, and a customer's national
 * identifier ciphertext, which is revealed only through its own audited endpoint. As in Epic 1
 * the mapping is written out rather than serialised from the entity, so an omission is a
 * decision on the page instead of an annotation somebody could remove.
 */
public final class CrmDtos {

    private CrmDtos() {
    }

    // ----------------------------------------------------------------- leads

    public record LeadResponse(UUID id, String name, String phone, String phoneNormalized,
                               String email, String source, String stage, String status,
                               UUID ownerUserId, UUID branchId, OffsetDateTime nextActionAt,
                               OffsetDateTime firstContactAt, String disqualifiedReason,
                               String interest, OffsetDateTime createdAt,
                               OffsetDateTime updatedAt) {

        public static LeadResponse from(Lead lead) {
            return new LeadResponse(lead.id(), lead.name(), lead.phone(), lead.phoneNormalized(),
                    lead.email(), lead.source(), lead.stage().code(), lead.status().code(),
                    lead.ownerUserId(), lead.branchId(), lead.nextActionAt(),
                    lead.firstContactAt(), lead.disqualifiedReason(), lead.interest(),
                    lead.createdAt(), lead.updatedAt());
        }
    }

    /**
     * {@code branchId} is the one optional placement hint: a manager capturing on behalf of
     * another branch. It is validated against the caller's tenant, so it can move a lead within
     * the tenant but never outside it.
     */
    public record CaptureLeadRequest(@NotBlank @Size(max = 200) String name,
                                     @NotBlank @Size(max = 40) String phone,
                                     @Email @Size(max = 320) String email,
                                     @Size(max = 80) String source,
                                     UUID branchId,
                                     String interest,
                                     boolean confirmDuplicate) {
    }

    public record AssignLeadRequest(@NotNull UUID ownerUserId, @Size(max = 500) String reason) {
    }

    public record BulkReassignRequest(@NotNull Collection<UUID> leadIds,
                                      @NotNull UUID ownerUserId,
                                      @NotBlank @Size(max = 500) String reason) {
    }

    public record BulkReassignResponse(int reassigned, UUID ownerUserId) {
    }

    public record DisqualifyLeadRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record ReactivateLeadRequest(@NotNull UUID ownerUserId,
                                        @NotBlank @Size(max = 500) String reason) {
    }

    public record NextActionRequest(@NotNull OffsetDateTime at) {
    }

    public record InterestRequest(String interest) {
    }

    // ------------------------------------------------------------- customers

    /** Carries {@code hasNationalId}, never the identifier or its ciphertext. */
    public record CustomerResponse(UUID id, String nameAr, String nameEn, String displayName,
                                   String phone, String phoneNormalized, String email,
                                   String address, boolean hasNationalId, UUID sourceLeadId,
                                   String status, OffsetDateTime createdAt,
                                   OffsetDateTime updatedAt) {

        public static CustomerResponse from(Customer customer) {
            return new CustomerResponse(customer.id(), customer.nameAr(), customer.nameEn(),
                    customer.displayName(), customer.phone(), customer.phoneNormalized(),
                    customer.email(), customer.address(), customer.hasNationalId(),
                    customer.sourceLeadId(), customer.status().code(), customer.createdAt(),
                    customer.updatedAt());
        }
    }

    public record ConvertLeadRequest(@Size(max = 200) String nameAr,
                                     @Size(max = 200) String nameEn,
                                     @Size(max = 500) String address,
                                     @Size(max = 40) String nationalId) {
    }

    public record CreateCustomerRequest(@Size(max = 200) String nameAr,
                                        @Size(max = 200) String nameEn,
                                        @NotBlank @Size(max = 40) String phone,
                                        @Email @Size(max = 320) String email,
                                        @Size(max = 500) String address,
                                        @Size(max = 40) String nationalId,
                                        boolean confirmDuplicate) {
    }

    public record UpdateCustomerRequest(@Size(max = 200) String nameAr,
                                        @Size(max = 200) String nameEn,
                                        @Size(max = 40) String phone,
                                        @Email @Size(max = 320) String email,
                                        @Size(max = 500) String address) {
    }

    public record SetNationalIdRequest(@NotBlank @Size(max = 40) String nationalId) {
    }

    /** A reason is mandatory: the audit entry is the point of the endpoint. */
    public record RevealNationalIdRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record NationalIdResponse(UUID customerId, String nationalId) {
    }

    // ------------------------------------------------------------ activities

    public record ActivityResponse(UUID id, String subjectType, UUID subjectId, String type,
                                   String body, OffsetDateTime occurredAt, UUID userId,
                                   OffsetDateTime createdAt) {

        public static ActivityResponse from(Activity activity) {
            return new ActivityResponse(activity.id(), activity.subject().code(),
                    activity.subjectId(), activity.type().code(), activity.body(),
                    activity.occurredAt(), activity.userId(), activity.createdAt());
        }
    }

    public record LogActivityRequest(@NotNull ActivitySubject subjectType,
                                     @NotNull UUID subjectId,
                                     @NotNull ActivityType type,
                                     @Size(max = 4000) String body,
                                     OffsetDateTime occurredAt) {
    }

    // ----------------------------------------------------------------- misc

    public record DuplicateMatchResponse(String recordType, UUID id, String name, String phone) {

        public static DuplicateMatchResponse from(DuplicateMatch match) {
            return new DuplicateMatchResponse(match.recordType(), match.id(), match.name(),
                    match.phoneNormalized());
        }
    }

    /** A deliberately small envelope: enough to page, with no total-count promise to keep. */
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
