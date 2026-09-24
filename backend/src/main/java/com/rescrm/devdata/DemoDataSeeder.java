package com.rescrm.devdata;

import com.rescrm.crm.service.CustomerService;
import com.rescrm.crm.service.LeadService;
import com.rescrm.identity.domain.Branch;
import com.rescrm.identity.service.InvitationService;
import com.rescrm.identity.service.TenantProvisioningService;
import com.rescrm.identity.service.TenantProvisioningService.ProvisionedTenant;
import com.rescrm.inventory.domain.Project;
import com.rescrm.inventory.domain.ProjectStatus;
import com.rescrm.inventory.service.ProjectService;
import com.rescrm.inventory.service.UnitService;
import com.rescrm.platform.money.CurrencyCode;
import com.rescrm.platform.money.Money;
import com.rescrm.platform.security.AuthenticatedPrincipal;
import com.rescrm.platform.security.Role;
import com.rescrm.platform.security.SecurityContext;
import com.rescrm.platform.tenancy.PlatformAuthenticationLookup;
import com.rescrm.platform.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Creates a tenant to sign in to, on the local profile only.
 *
 * <p>There is no way to create the first tenant over HTTP. Provisioning is an in-process
 * service with no controller — deliberately, since doc 23 lists no endpoint for it and the
 * platform-admin surface that would own one does not exist yet. That is fine for production,
 * where a tenant is provisioned by an operator, and useless for a developer who has just
 * started the application and wants to see a screen.
 *
 * <p>So this seeds one, with data chosen to exercise the whole slice rather than to look
 * full: units in several states so the status filter has something to filter, a customer and
 * a lead so a hold has a party to be held for, and an agent as well as an owner so branch
 * scoping is visible rather than theoretical.
 *
 * <h2>Why this cannot run anywhere real</h2>
 *
 * <p>It creates accounts whose passwords are written in this file. Three things keep it off
 * any other environment: {@code @Profile("local")}, a property that can switch it off within
 * that profile, and the fact that it does nothing at all if the demo tenant already exists.
 * The profile is the one that matters — the other two are conveniences.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "crm.demo-data.enabled", havingValue = "true",
        matchIfMissing = true)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    /** The company key typed at sign-in. */
    private static final String SLUG = "demo";

    private static final String OWNER_EMAIL = "owner@demo.test";
    private static final String AGENT_EMAIL = "agent@demo.test";

    /**
     * Long enough to satisfy the twelve-character minimum invitation acceptance enforces,
     * and obviously worthless to anybody who finds it.
     */
    private static final String PASSWORD = "demo-password-1";

    private final PlatformAuthenticationLookup lookup;
    private final TenantProvisioningService provisioning;
    private final InvitationService invitations;
    private final ProjectService projects;
    private final UnitService units;
    private final CustomerService customers;
    private final LeadService leads;

    public DemoDataSeeder(PlatformAuthenticationLookup lookup,
                          TenantProvisioningService provisioning,
                          InvitationService invitations, ProjectService projects,
                          UnitService units, CustomerService customers, LeadService leads) {
        this.lookup = lookup;
        this.provisioning = provisioning;
        this.invitations = invitations;
        this.projects = projects;
        this.units = units;
        this.customers = customers;
        this.leads = leads;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (lookup.activeTenantBySlug(SLUG).isPresent()) {
            log.info("Demo tenant '{}' already exists; sign in as {} / {}",
                    SLUG, OWNER_EMAIL, PASSWORD);
            return;
        }

        try {
            seed();
        } catch (RuntimeException e) {
            // Never stop the application from starting. A developer with a half-seeded
            // database can drop it; a developer whose application will not boot because of
            // demo data has a much worse afternoon.
            log.warn("Demo data could not be seeded; the application is running without it", e);
        }
    }

    private void seed() {
        ProvisionedTenant tenant = provisioning.provision(
                "Demo Real Estate", SLUG, "own_inventory", "Main branch",
                "Demo Owner", OWNER_EMAIL, PASSWORD);

        UUID tenantId = tenant.tenant().id();
        Branch branch = tenant.initialBranch();
        AuthenticatedPrincipal owner = new AuthenticatedPrincipal(
                tenant.owner().id(), tenantId, Role.OWNER, null);

        String agentToken = as(tenantId, owner, () ->
                invitations.invite(AGENT_EMAIL, Role.SALES_AGENT, branch.id()).rawToken());
        invitations.accept(agentToken, "Demo Agent", PASSWORD);

        as(tenantId, owner, () -> {
            // Own-inventory, so no developer: C10 forbids one where the project belongs to
            // the tenant itself, and the policy layer would refuse it anyway.
            Project project = projects.create("own_inventory", null, "أبراج النيل",
                    "Nile Towers", "New Cairo", LocalDate.now().plusYears(2));

            // Draft projects are not sellable, and a hold on one is refused. Activating it
            // here is what makes the slice's main path work at all.
            projects.changeStatus(project.id(), ProjectStatus.ACTIVE, "Demo data");

            seedUnits(project.id());
            seedParties(branch.id());
            return null;
        });

        announce();
    }

    private void seedUnits(UUID projectId) {
        record Spec(String code, String type, String area, String floor, String view,
                    String price) { }

        List<Spec> specs = List.of(
                new Spec("A-101", "Apartment", "142.50", "1", "Garden", "4250000.00"),
                new Spec("A-102", "Apartment", "118.00", "1", "Street", "3480000.00"),
                new Spec("A-201", "Apartment", "142.50", "2", "Garden", "4390000.00"),
                new Spec("A-202", "Apartment", "165.75", "2", "Pool", "5125000.50"),
                new Spec("B-101", "Duplex", "240.00", "1", "Garden", "7900000.00"),
                new Spec("B-102", "Duplex", "240.00", "1", "Pool", "8150000.00"),
                new Spec("P-01", "Penthouse", "310.25", "8", "Skyline", "12750000.00"),
                new Spec("V-01", "Villa", "420.00", "0", "Garden", "15400000.00"));

        Map<String, UUID> created = new LinkedHashMap<>();
        specs.forEach(spec -> created.put(spec.code(),
                units.create(projectId, null, spec.code(), spec.type(),
                        new BigDecimal(spec.area()), spec.floor(), spec.view(),
                        Money.of(spec.price(), CurrencyCode.EGP)).id()));

        // One unit off the market for a stated reason, so the status filter has something
        // other than 'available' to show and the default view visibly excludes something.
        units.block(created.get("V-01"),
                "Show villa; not for sale until the sales centre moves");
    }

    private void seedParties(UUID branchId) {
        customers.create("منى حسن", "Mona Hassan", "+201001234567",
                "mona@example.test", "Maadi, Cairo", null, false);
        customers.create("أحمد فاروق", "Ahmed Farouk", "+201009876543",
                "ahmed@example.test", "Zamalek, Cairo", null, false);

        leads.capture("Sara Ibrahim", "+201112223334", "sara@example.test",
                "Website", branchId, null, false);
        leads.capture("Khaled Nour", "+201115556667", "khaled@example.test",
                "Referral", branchId, null, false);
    }

    /**
     * Runs a block as a given principal inside a tenant.
     *
     * <p>The tenant is established BEFORE anything transactional is entered, for the reason
     * {@code TenantProvisioningService} documents at length: a connection is stamped when it
     * is taken, and taking one before the tenant is known stamps it with nothing.
     */
    private <T> T as(UUID tenantId, AuthenticatedPrincipal principal, Supplier<T> action) {
        return TenantContext.callAs(tenantId, () -> {
            SecurityContext.set(principal);
            try {
                return action.get();
            } finally {
                SecurityContext.clear();
            }
        });
    }

    private void announce() {
        log.info("Demo data seeded. Sign in at http://localhost:5173 with:");
        log.info("    company key   {}", SLUG);
        log.info("    owner         {}   password {}", OWNER_EMAIL, PASSWORD);
        log.info("    sales agent   {}   password {}", AGENT_EMAIL, PASSWORD);
        log.info("Nile Towers: 8 units (1 blocked), 2 customers, 2 leads.");
        log.info("Switch this off with crm.demo-data.enabled=false");
    }
}
