package com.rescrm.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rescrm.inventory.domain.ProjectStatus;
import com.rescrm.inventory.domain.UnitStatus;
import com.rescrm.platform.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.format.support.DefaultFormattingConversionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The API accepts the vocabulary it publishes.
 *
 * <p>This exists because writing the first real client found that it did not.
 * {@code GET /units} returns {@code "status":"available"}, but its own {@code ?status=}
 * parameter only accepted {@code AVAILABLE}, because Spring binds enums with
 * {@link Enum#valueOf}. A client that filtered by a value the API had just given it got a
 * 400 — invisible from the server side, and the first thing a client hits.
 *
 * <p>The conversion service is built the way Spring MVC builds it: the defaults first,
 * including Spring's own {@code StringToEnumConverterFactory}, and the configurer after. That
 * ordering is the test. Registering a converter for a pair something else already handles is
 * not the same as being the one that runs.
 */
@DisplayName("Coded enums on the wire")
class CodedEnumConverterTest {

    private final DefaultFormattingConversionService conversion = conversionService();

    private static DefaultFormattingConversionService conversionService() {
        DefaultFormattingConversionService service = new DefaultFormattingConversionService();
        new CodedEnumConverters().addFormatters(service);
        return service;
    }

    @Nested
    @DisplayName("as a request parameter")
    class AsRequestParameter {

        @Test
        @DisplayName("accepts the code a response would have carried")
        void accepts_the_code() {
            assertThat(conversion.convert("available", UnitStatus.class))
                    .isEqualTo(UnitStatus.AVAILABLE);
            assertThat(conversion.convert("reserved", UnitStatus.class))
                    .isEqualTo(UnitStatus.RESERVED);
            assertThat(conversion.convert("sold_out", ProjectStatus.class))
                    .isEqualTo(ProjectStatus.SOLD_OUT);
        }

        @Test
        @DisplayName("still accepts the Java name, so nothing that worked stops working")
        void still_accepts_the_name() {
            assertThat(conversion.convert("AVAILABLE", UnitStatus.class))
                    .isEqualTo(UnitStatus.AVAILABLE);
            assertThat(conversion.convert("Available", UnitStatus.class))
                    .isEqualTo(UnitStatus.AVAILABLE);
        }

        @Test
        @DisplayName("leaves enums that are not coded exactly as they were")
        void ordinary_enums_are_untouched() {
            // Role has no wire code and binds by name. A converter factory that swallowed
            // every enum would be a much larger change than the one intended.
            assertThat(conversion.convert("OWNER", Role.class)).isEqualTo(Role.OWNER);
        }

        @Test
        @DisplayName("still refuses a value that is neither")
        void nonsense_is_refused() {
            assertThatThrownBy(() -> conversion.convert("not-a-status", UnitStatus.class))
                    .isInstanceOf(ConversionFailedException.class);
        }
    }

    @Nested
    @DisplayName("in a request body")
    class InRequestBody {

        /**
         * Built the way the application's is: with the module registered.
         *
         * <p>A bare {@code new ObjectMapper()} would be testing something the application
         * never uses. This mirrors {@code MoneySerializationTest}, which registers
         * {@code MoneyModule} for the same reason — and the module is taken from the
         * configuration method that supplies it in production, so the two cannot drift.
         *
         * <p>That the application's own mapper really does carry it is asserted separately,
         * through the real Spring context, in {@code HealthAndErrorEnvelopeIT}: a module
         * that exists and is never registered is precisely the failure this pair is for.
         */
        private final ObjectMapper mapper =
                new ObjectMapper().registerModule(new CodedEnumConverters().codedEnumModule());

        @Test
        @DisplayName("accepts the code, and the name")
        void accepts_both() throws Exception {
            assertThat(mapper.readValue("\"draft\"", ProjectStatus.class))
                    .isEqualTo(ProjectStatus.DRAFT);
            assertThat(mapper.readValue("\"DRAFT\"", ProjectStatus.class))
                    .isEqualTo(ProjectStatus.DRAFT);
            assertThat(mapper.readValue("\"available\"", UnitStatus.class))
                    .isEqualTo(UnitStatus.AVAILABLE);
        }

        @Test
        @DisplayName("refuses a value that is neither")
        void nonsense_is_refused() {
            assertThatThrownBy(() -> mapper.readValue("\"not-a-status\"", ProjectStatus.class))
                    .isInstanceOf(Exception.class);
        }
    }
}
