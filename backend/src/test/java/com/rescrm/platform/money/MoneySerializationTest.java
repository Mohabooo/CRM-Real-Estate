package com.rescrm.platform.money;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rescrm.platform.money.jackson.MoneyModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FIN-017 — money crosses the API as a string, never as a JSON number (doc 23).
 *
 * <p>The rule exists because a JSON number invites every consumer, JavaScript above all, to
 * parse it into a binary float — the exact representation the financial design avoids.
 */
@DisplayName("Money serialization")
class MoneySerializationTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new MoneyModule());
    }

    @Test
    @DisplayName("serializes as a string-valued object, not a JSON number")
    void serializes_as_string() throws Exception {
        String json = objectMapper.writeValueAsString(Money.of("2850000.00", CurrencyCode.EGP));

        assertThat(json).isEqualTo("{\"amount\":\"2850000.00\",\"currency\":\"EGP\"}");
        // The amount must be quoted. An unquoted 2850000.00 is a float waiting to happen.
        assertThat(json).contains("\"2850000.00\"");
    }

    @Test
    @DisplayName("round-trips without loss")
    void round_trips() throws Exception {
        Money original = Money.of("75703.28", CurrencyCode.EGP);
        String json = objectMapper.writeValueAsString(original);

        assertThat(objectMapper.readValue(json, Money.class)).isEqualTo(original);
    }

    @Test
    @DisplayName("accepts a bare string as EGP")
    void accepts_bare_string() throws Exception {
        assertThat(objectMapper.readValue("\"1234.56\"", Money.class))
                .isEqualTo(Money.of("1234.56", CurrencyCode.EGP));
    }

    @Test
    @DisplayName("rejects a bare JSON number")
    void rejects_numeric_input() {
        // Accepting this would mean trusting a value that has already passed through a float
        // on the client, defeating the string-only convention.
        assertThatThrownBy(() -> objectMapper.readValue("1234.56", Money.class))
                .hasMessageContaining("numeric money values are rejected");
    }

    @Test
    @DisplayName("percentages serialize as strings too")
    void percentage_serializes_as_string() throws Exception {
        String json = objectMapper.writeValueAsString(Percentage.of("2.5"));

        assertThat(json).isEqualTo("\"2.5000\"");
        assertThat(objectMapper.readValue(json, Percentage.class)).isEqualTo(Percentage.of("2.5"));
    }
}
