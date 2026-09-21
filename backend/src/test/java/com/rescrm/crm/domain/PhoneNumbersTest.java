package com.rescrm.crm.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Duplicate detection is only as good as normalisation (E2-S1).
 *
 * <p>The same Egyptian mobile is written at least five ways in practice, and two records that
 * disagree only about a leading zero are the duplicates nobody catches. Every spelling below
 * has to reduce to one canonical string or the warning never fires.
 */
@DisplayName("Egyptian phone normalisation")
class PhoneNumbersTest {

    private static final String CANONICAL = "+201001234567";

    @Nested
    @DisplayName("the same number, however it was typed")
    class SameNumber {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "+201001234567",
                "00201001234567",
                "201001234567",
                "01001234567",
                "1001234567",
                "+20 100 123 4567",
                "+20-100-123-4567",
                "(+20) 100 1234567",
                " 01001234567 ",
                "0100 123 4567",
        })
        @DisplayName("normalises to the canonical form")
        void normalises(String written) {
            assertThat(PhoneNumbers.normalize(written)).isEqualTo(CANONICAL);
        }

        @Test
        @DisplayName("so two records written differently are detected as duplicates")
        void two_spellings_match_each_other() {
            assertThat(PhoneNumbers.normalize("01001234567"))
                    .isEqualTo(PhoneNumbers.normalize("+20 100 123 4567"));
        }
    }

    @Nested
    @DisplayName("all four Egyptian mobile prefixes")
    class Prefixes {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "01012345678, +201012345678",
                "01112345678, +201112345678",
                "01212345678, +201212345678",
                "01512345678, +201512345678",
        })
        @DisplayName("are recognised")
        void recognised(String written, String expected) {
            assertThat(PhoneNumbers.normalize(written)).isEqualTo(expected);
            assertThat(PhoneNumbers.isEgyptianMobile(expected)).isTrue();
        }

        @Test
        @DisplayName("013 is not one of them, and is kept as typed rather than refused")
        void unknown_prefix_is_not_an_egyptian_mobile() {
            String normalized = PhoneNumbers.normalize("01312345678");
            assertThat(PhoneNumbers.isEgyptianMobile(normalized)).isFalse();
            assertThat(normalized).isEqualTo("01312345678");
        }
    }

    @Nested
    @DisplayName("numbers it does not recognise")
    class Unrecognised {

        /**
         * The important half. A lead is unqualified demand, often typed from a scribble, and a
         * validator that rejects an unusual number loses the lead entirely — or, worse,
         * teaches the agent to type a fake one that passes. So an unrecognised number is kept
         * and normalised as best it can be, never refused.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "+44 20 7946 0958",
                "+1 (555) 010-9999",
                "0224123456",
                "12345",
        })
        @DisplayName("are still stored, not rejected")
        void kept_rather_than_refused(String written) {
            assertThat(PhoneNumbers.normalize(written)).isNotBlank();
            assertThat(PhoneNumbers.isEgyptianMobile(PhoneNumbers.normalize(written))).isFalse();
        }

        @Test
        @DisplayName("an international number keeps its own country code")
        void keeps_foreign_country_code() {
            assertThat(PhoneNumbers.normalize("+44 20 7946 0958")).isEqualTo("+442079460958");
        }
    }

    @Nested
    @DisplayName("absent input")
    class Absent {

        @Test
        @DisplayName("normalizeOrNull answers null rather than throwing")
        void null_in_null_out() {
            assertThat(PhoneNumbers.normalizeOrNull(null)).isNull();
            assertThat(PhoneNumbers.normalizeOrNull("   ")).isNull();
            assertThat(PhoneNumbers.normalizeOrNull("(-)")).isNull();
        }

        @Test
        @DisplayName("and is not confused by punctuation alone")
        void punctuation_only_is_not_a_number() {
            assertThat(PhoneNumbers.isEgyptianMobile("----")).isFalse();
        }
    }
}
