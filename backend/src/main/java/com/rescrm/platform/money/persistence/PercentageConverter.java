package com.rescrm.platform.money.persistence;

import com.rescrm.platform.money.Percentage;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.math.BigDecimal;

/**
 * Maps {@link Percentage} onto a {@code NUMERIC(18,4)} column, matching the rate convention
 * in doc 22, section 8.
 */
@Converter(autoApply = false)
public class PercentageConverter implements AttributeConverter<Percentage, BigDecimal> {

    @Override
    public BigDecimal convertToDatabaseColumn(Percentage attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public Percentage convertToEntityAttribute(BigDecimal dbData) {
        return dbData == null ? null : Percentage.of(dbData);
    }
}
