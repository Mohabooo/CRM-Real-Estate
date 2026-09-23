package com.rescrm.reservations.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenant reservation settings, and what happens when they are absent or wrong.
 *
 * <p>The defaulting behaviour is the point. A tenant that has never configured reservations
 * must still be able to make one, and a settings blob somebody hand-edited badly must not
 * stop an agent with a customer in front of them: the wrong hold length is recoverable, a
 * blocked sale is not.
 */
@DisplayName("Reservation settings")
class ReservationSettingsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ReservationSettings from(String json) {
        return ReservationSettings.from(json, mapper);
    }

    @Test
    @DisplayName("reads the tenant's own values")
    void reads_configured_values() {
        ReservationSettings settings =
                from("{\"reservations\":{\"holdDays\":3,\"maxExtensionDays\":2}}");

        assertThat(settings.defaultHold()).isEqualTo(Duration.ofDays(3));
        assertThat(settings.maxExtension()).isEqualTo(Duration.ofDays(2));
    }

    @Test
    @DisplayName("falls back to the defaults when the section is absent")
    void defaults_when_absent() {
        assertThat(from("{}").defaultHold()).isEqualTo(ReservationSettings.DEFAULT_HOLD);
        assertThat(from("{\"agingBuckets\":[30,60,90]}").maxExtension())
                .isEqualTo(ReservationSettings.DEFAULT_MAX_EXTENSION);
    }

    @Test
    @DisplayName("falls back rather than failing on null, blank or malformed settings")
    void defaults_rather_than_failing() {
        assertThat(from(null).defaultHold()).isEqualTo(ReservationSettings.DEFAULT_HOLD);
        assertThat(from("   ").defaultHold()).isEqualTo(ReservationSettings.DEFAULT_HOLD);
        assertThat(from("{not json at all").defaultHold())
                .isEqualTo(ReservationSettings.DEFAULT_HOLD);
    }

    @Test
    @DisplayName("ignores a nonsensical hold length")
    void ignores_nonsense() {
        assertThat(from("{\"reservations\":{\"holdDays\":0}}").defaultHold())
                .as("a hold of zero days expires before the agent finishes typing")
                .isEqualTo(ReservationSettings.DEFAULT_HOLD);
        assertThat(from("{\"reservations\":{\"holdDays\":-5}}").defaultHold())
                .isEqualTo(ReservationSettings.DEFAULT_HOLD);
        assertThat(from("{\"reservations\":{\"holdDays\":\"seven\"}}").defaultHold())
                .isEqualTo(ReservationSettings.DEFAULT_HOLD);
    }

    @Test
    @DisplayName("but honours a zero extension limit, which is a real choice")
    void zero_extension_is_meaningful() {
        // Unlike a zero-day hold, "no extensions at all" is a policy a tenant may want.
        assertThat(from("{\"reservations\":{\"maxExtensionDays\":0}}").maxExtension())
                .isEqualTo(Duration.ZERO);
    }
}
