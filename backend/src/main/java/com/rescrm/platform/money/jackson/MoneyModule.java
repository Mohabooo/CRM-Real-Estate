package com.rescrm.platform.money.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.rescrm.platform.money.CurrencyCode;
import com.rescrm.platform.money.Money;
import com.rescrm.platform.money.Percentage;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * Serializes money and rates as JSON <em>strings</em>, never as JSON numbers.
 *
 * <p>Doc 23 is explicit about this: {@code "2850000.00"}, not {@code 2850000.00}. A JSON
 * number invites every consumer — JavaScript above all — to parse it into a binary float,
 * which is precisely the representation the entire financial design exists to avoid. Emitting
 * a string makes that mistake impossible to make accidentally on the client.
 *
 * <p>Registered automatically through {@code MoneyJacksonConfiguration}.
 */
public class MoneyModule extends SimpleModule {

    private static final long serialVersionUID = 1L;

    public MoneyModule() {
        super("MoneyModule");
        addSerializer(Money.class, new MoneySerializer());
        addDeserializer(Money.class, new MoneyDeserializer());
        addSerializer(Percentage.class, new PercentageSerializer());
        addDeserializer(Percentage.class, new PercentageDeserializer());
    }

    /** Writes {@code {"amount":"2850000.00","currency":"EGP"}}. */
    static final class MoneySerializer extends JsonSerializer<Money> {
        @Override
        public void serialize(Money value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeStartObject();
            gen.writeStringField("amount", value.toPlainString());
            gen.writeStringField("currency", value.currency().isoCode());
            gen.writeEndObject();
        }
    }

    /**
     * Reads either the object form above or a bare string, which is treated as EGP.
     *
     * <p>A bare JSON number is rejected rather than accepted leniently: accepting it would
     * mean the value had already passed through a float on the client, and silently trusting
     * it would defeat the string-only convention.
     */
    static final class MoneyDeserializer extends JsonDeserializer<Money> {
        @Override
        public Money deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            switch (parser.currentToken()) {
                case VALUE_STRING:
                    return Money.of(parser.getText(), CurrencyCode.EGP);
                case START_OBJECT: {
                    String amount = null;
                    String currency = null;
                    while (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_OBJECT) {
                        String field = parser.currentName();
                        parser.nextToken();
                        if ("amount".equals(field)) {
                            amount = parser.getText();
                        } else if ("currency".equals(field)) {
                            currency = parser.getText();
                        }
                    }
                    if (amount == null) {
                        throw new IOException("Money object requires an 'amount' field");
                    }
                    CurrencyCode code = currency == null
                            ? CurrencyCode.EGP
                            : CurrencyCode.fromIsoCode(currency);
                    return Money.of(amount, code);
                }
                default:
                    throw new IOException(
                            "Money must be a string or an object, not "
                                    + parser.currentToken()
                                    + "; numeric money values are rejected to prevent float parsing");
            }
        }
    }

    static final class PercentageSerializer extends JsonSerializer<Percentage> {
        @Override
        public void serialize(Percentage value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeString(value.value().toPlainString());
        }
    }

    static final class PercentageDeserializer extends JsonDeserializer<Percentage> {
        @Override
        public Percentage deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            if (parser.currentToken() == com.fasterxml.jackson.core.JsonToken.VALUE_STRING) {
                return Percentage.of(parser.getText());
            }
            return Percentage.of(new BigDecimal(parser.getText()));
        }
    }
}
