package com.rescrm.commercialmodel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Commercial model codes")
class CommercialModelCodesTest {

    @Test
    @DisplayName("accepts the two stored spellings")
    void accepts_known_codes() {
        assertThat(CommercialModelCodes.isValid("own_inventory")).isTrue();
        assertThat(CommercialModelCodes.isValid("brokered_inventory")).isTrue();
    }

    @Test
    @DisplayName("rejects anything else, including the enum's own name")
    void rejects_unknown() {
        assertThat(CommercialModelCodes.isValid("OWN_INVENTORY")).isFalse();
        assertThat(CommercialModelCodes.isValid("rent_to_own")).isFalse();
        assertThat(CommercialModelCodes.isValid(null)).isFalse();
        assertThatThrownBy(() -> CommercialModelCodes.requireValid("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("own_inventory");
    }

    @Test
    @DisplayName("covers every model, so adding one cannot be forgotten here")
    void covers_every_model() {
        for (CommercialModel model : CommercialModel.values()) {
            assertThat(CommercialModelCodes.isValid(
                    model.name().toLowerCase(java.util.Locale.ROOT))).isTrue();
        }
    }
}
