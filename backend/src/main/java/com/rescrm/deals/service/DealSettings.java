package com.rescrm.deals.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rescrm.platform.money.Money;
import com.rescrm.platform.money.Percentage;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * The tenant's deal rules, read out of {@code tenants.settings}.
 *
 * <p>R-DISC-5 requires manager approval for discounts above a threshold, and doc 17 section
 * 12 gives that threshold as configuration with **no default** — "set per tenant". So an
 * absent setting means no threshold, and no discount needs approval. That is the honest
 * reading: inventing a default would silently gate concessions at a number nobody chose,
 * and the first anybody would hear of it is an agent unable to close.
 *
 * <p>Expressed as a percentage of gross rather than an amount, so it travels between a
 * 2,000,000 unit and a 20,000,000 one without being reset.
 */
public record DealSettings(Optional<Percentage> discountApprovalThreshold) {

    private static final String SECTION = "deals";
    private static final String THRESHOLD = "discountApprovalThresholdPercent";

    public static DealSettings from(String settingsJson, ObjectMapper mapper) {
        try {
            JsonNode section = mapper.readTree(
                            settingsJson == null || settingsJson.isBlank() ? "{}" : settingsJson)
                    .path(SECTION);
            JsonNode threshold = section.path(THRESHOLD);
            if (threshold.isNumber() && threshold.decimalValue().signum() >= 0) {
                return new DealSettings(Optional.of(Percentage.of(threshold.decimalValue())));
            }
        } catch (Exception malformed) {
            // A settings blob somebody hand-edited badly must not stop a deal being drafted.
            // No threshold is the documented default, and it is the safe one to fall back to:
            // it blocks nothing that was not already blocked.
        }
        return new DealSettings(Optional.empty());
    }

    /**
     * Whether the discount standing on a deal needs a manager's approval before activation.
     *
     * <p>Compared against the discount as a proportion of GROSS, matching how the discount
     * itself is computed (R-DISC-1). A threshold of zero means every discount needs
     * approval, which is a real policy and distinct from having no threshold at all.
     */
    public boolean approvalRequiredFor(Money grossValue, Money totalDiscount) {
        if (discountApprovalThreshold.isEmpty() || totalDiscount.isZero()) {
            return false;
        }
        BigDecimal percentOfGross = totalDiscount.amount()
                .multiply(BigDecimal.valueOf(100))
                .divide(grossValue.amount(), 4, java.math.RoundingMode.HALF_UP);

        return percentOfGross.compareTo(discountApprovalThreshold.get().value()) > 0;
    }
}
