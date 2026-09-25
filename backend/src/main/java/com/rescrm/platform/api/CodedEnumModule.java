package com.rescrm.platform.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.util.Locale;

/**
 * Lets a request <em>body</em> carry the same enum spelling a response does.
 *
 * <p>The sibling of {@link CodedEnumConverters}, which does this for query parameters and
 * path variables. Both exist because Spring and Jackson bind enums by {@link Enum#valueOf},
 * so {@code "status":"draft"} — the value the API itself just returned — would be a 400
 * while {@code "DRAFT"} worked.
 *
 * <p>A modifier rather than a deserializer per enum. A list of registrations is a list to
 * keep in step, and the one that gets forgotten is the one added last; implementing
 * {@link CodedEnum} is the whole registration.
 *
 * <p>This replaces the {@code @JsonCreator}-annotated {@code fromCode} factories that used
 * to do the same job one enum at a time. Those annotations have been removed rather than
 * left in place: a modifier-supplied deserializer takes precedence over them, so leaving
 * them would have been metadata that looked load-bearing and was not. The {@code fromCode}
 * methods themselves remain — production code reads stored codes through them.
 *
 * <p>It also matters for {@code finance}. An architecture rule keeps that package free of
 * framework types so every rule in doc 17 stays unit-testable with no infrastructure, and
 * {@code InstallmentKind} is a {@link CodedEnum} that lives there. Handling the mapping here
 * is what lets the calculators keep their independence and still deserialize.
 */
public class CodedEnumModule extends SimpleModule {

    private static final long serialVersionUID = 1L;

    public CodedEnumModule() {
        super("CodedEnumModule");
    }

    @Override
    public void setupModule(SetupContext context) {
        super.setupModule(context);
        context.addBeanDeserializerModifier(new CodedEnumDeserializerModifier());
    }

    static final class CodedEnumDeserializerModifier extends BeanDeserializerModifier {

        private static final long serialVersionUID = 1L;

        @Override
        public JsonDeserializer<?> modifyEnumDeserializer(DeserializationConfig config,
                                                          JavaType type,
                                                          BeanDescription description,
                                                          JsonDeserializer<?> deserializer) {
            Class<?> raw = type.getRawClass();
            if (!CodedEnum.class.isAssignableFrom(raw)) {
                return deserializer;
            }
            return new CodedEnumDeserializer(raw);
        }
    }

    static final class CodedEnumDeserializer extends JsonDeserializer<Object> {

        /**
         * Held as {@code Class<?>} rather than {@code Class<? extends Enum<?>>}: the
         * modifier only knows the raw class, and the wildcard capture that produces cannot
         * be narrowed without an unchecked cast that buys nothing. Every constant is a
         * {@link CodedEnum} because that is what the modifier checked before constructing
         * this.
         */
        private final Class<?> type;

        CodedEnumDeserializer(Class<?> type) {
            this.type = type;
        }

        @Override
        public Object deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            String raw = parser.getValueAsString();
            String trimmed = raw == null ? "" : raw.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            for (Object constant : type.getEnumConstants()) {
                if (((CodedEnum) constant).code().equalsIgnoreCase(trimmed)) {
                    return constant;
                }
            }
            // The Java name still works, so nothing that predates this stops working.
            for (Object constant : type.getEnumConstants()) {
                if (((Enum<?>) constant).name().equalsIgnoreCase(trimmed)) {
                    return constant;
                }
            }
            // Names the values that would have worked. A 400 saying only "invalid" leaves
            // the caller guessing between the code and the name, which is the confusion
            // this whole mechanism exists to remove.
            return context.handleWeirdStringValue(type, trimmed,
                    "expected one of: %s", acceptedValues());
        }

        private String acceptedValues() {
            StringBuilder accepted = new StringBuilder();
            for (Object constant : type.getEnumConstants()) {
                accepted.append(accepted.length() == 0 ? "" : ", ")
                        .append(((CodedEnum) constant).code().toLowerCase(Locale.ROOT));
            }
            return accepted.toString();
        }
    }
}
