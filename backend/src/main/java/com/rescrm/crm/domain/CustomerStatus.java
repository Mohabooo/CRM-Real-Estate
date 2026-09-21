package com.rescrm.crm.domain;

/**
 * Doc 22 gives customers a status column; no document defines its values or transitions.
 *
 * <p>Two are implemented — the record is in use, or it is not — and nothing more is invented.
 * A customer is never deleted: deals, payments and commissions reference them.
 */
public enum CustomerStatus {

    ACTIVE("active"),
    INACTIVE("inactive");

    private final String code;

    CustomerStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static CustomerStatus fromCode(String code) {
        for (CustomerStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown customer status '" + code + "'");
    }
}
