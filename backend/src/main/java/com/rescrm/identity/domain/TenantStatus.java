package com.rescrm.identity.domain;

import java.util.Set;

/**
 * Tenant lifecycle: {@code provisioning → active → suspended → closed} (doc 16, section 1).
 *
 * <p>Only those three transitions are permitted. Reactivating a suspended tenant and closing
 * an active one directly are both plausible and neither is described by any document, so
 * neither is implemented — see the Epic 1 report's open questions. Inventing them here would
 * bury a business decision in a state table.
 */
public enum TenantStatus {

    PROVISIONING("provisioning"),
    ACTIVE("active"),
    SUSPENDED("suspended"),
    CLOSED("closed");

    private final String code;

    TenantStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static TenantStatus fromCode(String code) {
        for (TenantStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown tenant status '" + code + "'");
    }

    private Set<TenantStatus> allowedNext() {
        return switch (this) {
            case PROVISIONING -> Set.of(ACTIVE);
            case ACTIVE -> Set.of(SUSPENDED);
            case SUSPENDED -> Set.of(CLOSED);
            case CLOSED -> Set.of();
        };
    }

    public boolean canTransitionTo(TenantStatus next) {
        return allowedNext().contains(next);
    }

    /** True while the tenant's users may use the system. */
    public boolean permitsAccess() {
        return this == ACTIVE;
    }
}
