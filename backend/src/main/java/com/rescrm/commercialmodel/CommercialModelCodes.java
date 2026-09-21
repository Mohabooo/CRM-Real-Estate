package com.rescrm.commercialmodel;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Validates a stored commercial-model code without exposing the enum.
 *
 * <p>Doc 16 section 1 gives the tenant a {@code default_commercial_model} used to pre-fill new
 * projects, which means a module outside this package has to persist the code. The containment
 * rule (doc 28, section 2, rule 5) forbids that module from depending on
 * {@link CommercialModel} itself — and rightly so, because a module holding the enum is one
 * {@code switch} away from branching on it.
 *
 * <p>This class is the compromise: the code round-trips as an opaque string everywhere else,
 * and the single authority on which strings are valid stays here.
 */
public final class CommercialModelCodes {

    private CommercialModelCodes() {
    }

    public static boolean isValid(String code) {
        return code != null && Arrays.stream(CommercialModel.values())
                .anyMatch(model -> codeOf(model).equals(code));
    }

    /** @throws IllegalArgumentException naming the accepted values, for a usable API error. */
    public static String requireValid(String code) {
        if (!isValid(code)) {
            throw new IllegalArgumentException("Unknown commercial model '" + code
                    + "'; expected one of " + validCodes());
        }
        return code;
    }

    public static String validCodes() {
        return Arrays.stream(CommercialModel.values())
                .map(CommercialModelCodes::codeOf)
                .collect(Collectors.joining(", "));
    }

    /**
     * The stored spelling of a model: {@code OWN_INVENTORY} becomes {@code own_inventory}.
     *
     * <p>Derived rather than added as a field on the enum, so Epic 0's
     * {@link CommercialModel} is left exactly as it was.
     */
    private static String codeOf(CommercialModel model) {
        return model.name().toLowerCase(Locale.ROOT);
    }
}
