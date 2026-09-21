package com.rescrm.platform.money.persistence;

import com.rescrm.platform.money.CurrencyCode;
import com.rescrm.platform.money.Money;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.math.BigDecimal;

/**
 * Maps {@link Money} onto a single {@code NUMERIC(18,2)} column.
 *
 * <p>Currency is not stored by this converter. In MVP every amount is EGP (doc 17, N9), and a
 * currency column on every money-bearing table would be fifteen columns of the same constant.
 * When multi-currency arrives, currency belongs on the owning row — one column per deal, not
 * per amount — so this converter is replaced rather than extended.
 *
 * <p>Not applied automatically ({@code autoApply = false}): entities opt in per field, so the
 * choice is visible at the point of mapping rather than acting invisibly across the schema.
 */
@Converter(autoApply = false)
public class MoneyAmountConverter implements AttributeConverter<Money, BigDecimal> {

    @Override
    public BigDecimal convertToDatabaseColumn(Money attribute) {
        return attribute == null ? null : attribute.amount();
    }

    @Override
    public Money convertToEntityAttribute(BigDecimal dbData) {
        return dbData == null ? null : Money.of(dbData, CurrencyCode.EGP);
    }
}
