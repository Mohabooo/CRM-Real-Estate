package com.rescrm.crm.domain;

/**
 * Whether a lead is still in the funnel (doc 18, section 1).
 *
 * <p>Both exits are recorded rather than deleted: doc 18 says a disqualified lead "leaves the
 * funnel, retained", and E2-S4 requires a converted lead to leave it "without deletion". The
 * row survives either way, which is what keeps attribution and reporting honest.
 */
public enum LeadStatus {

    ACTIVE("active"),
    CONVERTED("converted"),
    DISQUALIFIED("disqualified");

    private final String code;

    LeadStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static LeadStatus fromCode(String code) {
        for (LeadStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown lead status '" + code + "'");
    }

    public boolean isActive() {
        return this == ACTIVE;
    }
}
