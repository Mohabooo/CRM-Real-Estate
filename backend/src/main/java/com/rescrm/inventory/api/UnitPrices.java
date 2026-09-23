package com.rescrm.inventory.api;

import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.money.CurrencyCode;
import com.rescrm.platform.money.Money;

import java.math.BigDecimal;

/**
 * Turns a price from the wire into {@link Money}, or a 400 saying why not.
 *
 * <p>Lives in the web layer on purpose. {@code Money.of} refuses more than two decimal places
 * rather than rounding, which is right for the domain and unhelpful as a 500 — so the
 * translation from "that is not a valid amount" to an error the client can act on happens
 * here, where HTTP status codes belong.
 */
final class UnitPrices {

    private UnitPrices() {
    }

    /** For a required amount supplied as a decimal string. */
    static Money require(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, field + " is required");
        }
        try {
            return Money.of(new BigDecimal(raw.trim()), CurrencyCode.EGP);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "'" + raw + "' is not a valid amount for " + field);
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
    }

    /** For an optional amount; null in, null out. */
    static Money parseOrNull(String raw) {
        return raw == null || raw.isBlank() ? null : require(raw, "amount");
    }

    /**
     * For a filter bound from a query parameter, which Spring has already parsed into a
     * {@code BigDecimal}. A filter is rounded rather than refused: "show me everything under
     * 2,500,000.005" is a range, not an amount of money, and rejecting it would be pedantry.
     */
    static Money parseOrNull(BigDecimal raw) {
        return raw == null ? null
                : Money.of(raw, CurrencyCode.EGP, java.math.RoundingMode.HALF_UP);
    }
}
