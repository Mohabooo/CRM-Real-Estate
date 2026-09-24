package com.rescrm.identity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The slug rules, which must agree exactly with {@code chk_tenants_slug_shape} in V6.
 *
 * <p>Anything this accepts and the database rejects would surface as an insert failing at
 * the last moment with a constraint name instead of a message somebody can act on.
 */
@DisplayName("A tenant slug")
class TenantSlugTest {

    @ParameterizedTest
    @ValueSource(strings = {"acme", "acme-realty", "nile-towers-2", "a1", "x9-y8-z7"})
    @DisplayName("accepts lowercase words joined by single hyphens")
    void accepts_well_formed_slugs(String slug) {
        assertThat(Tenant.requireSlug(slug)).isEqualTo(slug);
    }

    @Test
    @DisplayName("trims and lowercases what it is given")
    void normalizes_before_checking() {
        assertThat(Tenant.requireSlug("  Acme-Realty  ")).isEqualTo("acme-realty");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "-acme", "acme-", "acme--realty", "acme realty",
            "acme_realty", "Acme!", "", "   "})
    @DisplayName("refuses anything the database CHECK would refuse")
    void refuses_malformed_slugs(String slug) {
        assertThatThrownBy(() -> Tenant.requireSlug(slug))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refuses one longer than the column allows")
    void refuses_an_overlong_slug() {
        assertThatThrownBy(() -> Tenant.requireSlug("a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "Acme Real Estate,     acme-real-estate",
            "'  --Nile Towers--  ', nile-towers",
            "Acme  Real   Estate!!, acme-real-estate",
            "Delta 9,              delta-9"
    })
    @DisplayName("can be derived from an ordinary company name")
    void derives_a_candidate(String name, String expected) {
        assertThat(Tenant.slugCandidate(name)).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"شركة النيل", "!!!", "  ", "北京房地产"})
    @DisplayName("yields nothing for a name that slugifies to nothing, rather than inventing one")
    void refuses_to_invent_a_candidate(String name) {
        assertThat(Tenant.slugCandidate(name))
                .as("an Arabic name must not become a meaningless slug")
                .isEmpty();
    }

    @Test
    @DisplayName("yields nothing for null")
    void null_has_no_candidate() {
        assertThat(Tenant.slugCandidate(null)).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("trims a derived candidate to the column width without leaving a trailing hyphen")
    void trims_a_long_candidate() {
        String longName = ("Very Long Company Name ").repeat(10);

        Optional<String> candidate = Tenant.slugCandidate(longName);

        assertThat(candidate).isPresent();
        assertThat(candidate.get()).hasSizeLessThanOrEqualTo(63);
        assertThat(candidate.get()).doesNotEndWith("-");
        assertThat(Tenant.requireSlug(candidate.get())).isEqualTo(candidate.get());
    }
}
