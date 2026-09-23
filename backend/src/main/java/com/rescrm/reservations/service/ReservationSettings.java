package com.rescrm.reservations.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;

/**
 * The tenant's reservation rules, read out of {@code tenants.settings}.
 *
 * <p>E4-S1 says the expiry "defaults from tenant settings" and E4-S3 says an extension is
 * "within a limit"; neither document fixes the numbers, so the defaults below are this
 * implementation's and are stated in one place rather than scattered as literals.
 *
 * <p>Seven days is a hold long enough to collect documents and short enough that a forgotten
 * one does not keep a unit off the market for a month. The extension limit is measured from
 * the hold's ORIGINAL expiry rather than its current one, which is the whole reason the
 * entity keeps both: a limit measured from "now" can be walked forward a day at a time
 * forever, which is not a limit.
 *
 * <p>A malformed or missing settings blob yields the defaults rather than an error. A tenant
 * that has never configured reservations should be able to make one.
 */
public record ReservationSettings(Duration defaultHold, Duration maxExtension) {

    static final Duration DEFAULT_HOLD = Duration.ofDays(7);
    static final Duration DEFAULT_MAX_EXTENSION = Duration.ofDays(7);

    private static final String SECTION = "reservations";
    private static final String HOLD_DAYS = "holdDays";
    private static final String MAX_EXTENSION_DAYS = "maxExtensionDays";

    public static ReservationSettings from(String settingsJson, ObjectMapper mapper) {
        Duration hold = DEFAULT_HOLD;
        Duration maxExtension = DEFAULT_MAX_EXTENSION;
        try {
            JsonNode section = mapper.readTree(
                    settingsJson == null || settingsJson.isBlank() ? "{}" : settingsJson)
                    .path(SECTION);
            hold = positiveDaysOr(section.path(HOLD_DAYS), DEFAULT_HOLD);
            maxExtension = nonNegativeDaysOr(section.path(MAX_EXTENSION_DAYS),
                    DEFAULT_MAX_EXTENSION);
        } catch (Exception malformed) {
            // Defaults. A settings blob somebody hand-edited badly must not stop an agent
            // placing a hold; the wrong number here is recoverable, a blocked sale is not.
        }
        return new ReservationSettings(hold, maxExtension);
    }

    private static Duration positiveDaysOr(JsonNode node, Duration fallback) {
        return node.isIntegralNumber() && node.asInt() > 0
                ? Duration.ofDays(node.asInt())
                : fallback;
    }

    private static Duration nonNegativeDaysOr(JsonNode node, Duration fallback) {
        // Zero is meaningful: a tenant that allows no extension at all.
        return node.isIntegralNumber() && node.asInt() >= 0
                ? Duration.ofDays(node.asInt())
                : fallback;
    }
}
