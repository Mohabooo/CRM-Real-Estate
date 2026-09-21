package com.rescrm.identity.service;

import com.rescrm.identity.domain.Tenant;
import com.rescrm.identity.repository.TenantRepository;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the caller's own tenant.
 *
 * <p>There is no {@code get(tenantId)}: the only tenant any caller may read is the one they
 * are authenticated for, and a method taking an id would be an invitation to pass one in from
 * a request. Provisioning and status changes live in {@link TenantProvisioningService}.
 */
@Service
public class TenantService {

    private final TenantRepository tenants;

    public TenantService(TenantRepository tenants) {
        this.tenants = tenants;
    }

    @Transactional(readOnly = true)
    public Tenant current() {
        return tenants.findById(TenantContext.require())
                .orElseThrow(() -> ApiException.notFound("Tenant"));
    }
}
