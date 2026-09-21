package com.rescrm.crm.service;

import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Raised when a create would duplicate an existing phone number and the caller has not yet
 * confirmed.
 *
 * <p>Carries the matches in the error envelope's details so the client can show the link that
 * E2-S1 requires. Retrying with confirmation succeeds — this is a question, not a refusal.
 */
public class DuplicateWarningException extends ApiException {

    private static final long serialVersionUID = 1L;

    public DuplicateWarningException(List<DuplicateMatch> matches) {
        super(ErrorCode.CONFLICT,
                "A record with this phone number already exists. Confirm to create anyway.",
                Map.of("duplicates", matches.stream()
                        .map(match -> Map.of(
                                "recordType", match.recordType(),
                                "id", match.id().toString(),
                                "name", match.name() == null ? "" : match.name(),
                                "phone", match.phoneNormalized()))
                        .collect(Collectors.toList())));
    }
}
