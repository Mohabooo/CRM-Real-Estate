package com.rescrm.deals.domain;

import com.rescrm.platform.api.CodedEnum;

/**
 * How a discount is expressed (R-DISC-1), as stored in {@code deal_discounts.kind}.
 *
 * <p>Only the label. What a discount comes to is {@link Concession}'s to answer, and this
 * enum deliberately has no method that computes it: a kind plus a loose number is the shape
 * that lets a percentage be added to an amount somewhere down the line, and the sealed type
 * exists so that cannot be written.
 */
public enum DiscountKind implements CodedEnum {

    PERCENT("percent"),
    FIXED("fixed");

    private final String code;

    DiscountKind(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }

    public static DiscountKind fromCode(String code) {
        for (DiscountKind kind : values()) {
            if (kind.code.equals(code) || kind.name().equalsIgnoreCase(code)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown discount kind '" + code + "'");
    }
}
