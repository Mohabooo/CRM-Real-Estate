package com.rescrm.platform.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Locale;

/**
 * Lets a request parameter carry the same enum spelling a response does.
 *
 * <p>Spring binds enums with {@link Enum#valueOf}, so {@code ?status=available} — the value
 * the API itself returned a moment earlier — would be a 400 while {@code ?status=AVAILABLE}
 * works. That asymmetry is invisible from the server side and obvious the first time a client
 * filters by a status it was given.
 *
 * <p>The name is still accepted, case-insensitively. Nothing that worked before stops
 * working; the code simply starts working too.
 */
@Configuration
public class CodedEnumConverters implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverterFactory(new CodedEnumConverterFactory());
    }

    /**
     * The same leniency for request bodies.
     *
     * <p>A parameter and a body field naming the same enum should not disagree about which
     * spellings are acceptable, so the two mechanisms are registered together.
     */
    @Bean
    public CodedEnumModule codedEnumModule() {
        return new CodedEnumModule();
    }

    /**
     * One factory for every {@link CodedEnum}, rather than a converter per enum.
     *
     * <p>A converter per enum is a list to keep in step, and the one that gets forgotten is
     * the one added last. Implementing the interface is the whole registration.
     */
    static class CodedEnumConverterFactory implements ConverterFactory<String, Enum<?>> {

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public <T extends Enum<?>> Converter<String, T> getConverter(Class<T> targetType) {
            return source -> (T) convert(source, (Class<? extends Enum>) targetType);
        }

        private static <E extends Enum<E>> E convert(String source, Class<E> targetType) {
            String trimmed = source == null ? "" : source.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            for (E constant : targetType.getEnumConstants()) {
                if (constant instanceof CodedEnum coded
                        && coded.code().equalsIgnoreCase(trimmed)) {
                    return constant;
                }
            }
            // Falls back to the Java name so nothing that worked before this existed breaks.
            return Enum.valueOf(targetType, trimmed.toUpperCase(Locale.ROOT));
        }
    }
}
