package com.rescrm.platform.tenancy;

import java.util.Optional;
import java.util.UUID;

/**
 * Holds the tenant the current thread is acting for.
 *
 * <p>Infrastructure only. Epic 0 establishes the holder and its contract; nothing populates it
 * yet because authentication arrives in Epic 1. It ships now because the tenancy architecture
 * (doc 28, section 4) depends on two independent isolation layers — PostgreSQL row-level
 * security keyed on {@code app.current_tenant_id}, and repository-level scoping — and both
 * read from this holder. Adding it later would mean revisiting every data-access path.
 *
 * <p>{@link #require()} throws rather than returning null or a default. A query that runs with
 * no tenant established is a bug, and the safe failure is to stop, not to quietly read across
 * tenants.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        CURRENT.set(tenantId);
    }

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * The current tenant, or an exception.
     *
     * @throws TenantContextMissingException when no tenant has been established
     */
    public static UUID require() {
        UUID tenantId = CURRENT.get();
        if (tenantId == null) {
            throw new TenantContextMissingException();
        }
        return tenantId;
    }

    public static boolean isSet() {
        return CURRENT.get() != null;
    }

    /**
     * Clears the holder. Mandatory at the end of every request: servlet threads are pooled, and
     * a leaked tenant id would let the next request read another tenant's data.
     */
    public static void clear() {
        CURRENT.remove();
    }

    /** Runs an action for a given tenant, restoring the previous value afterwards. */
    public static <T> T callAs(UUID tenantId, java.util.function.Supplier<T> action) {
        UUID previous = CURRENT.get();
        try {
            set(tenantId);
            return action.get();
        } finally {
            if (previous == null) {
                clear();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** Thrown when tenant-scoped work is attempted with no tenant established. */
    public static final class TenantContextMissingException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        TenantContextMissingException() {
            super("No tenant is established for the current thread; tenant-scoped access is refused");
        }
    }
}
