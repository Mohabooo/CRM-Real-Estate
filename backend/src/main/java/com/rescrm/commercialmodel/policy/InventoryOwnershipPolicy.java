package com.rescrm.commercialmodel.policy;

/**
 * The first of doc 25 section 6's eight policy points: who owns the inventory, and does a
 * developer record exist for it.
 *
 * <p>Doc 21 section 2a lists this policy's owning module as {@code inventory}. The interface
 * lives here instead, and the reason is the containment rule it serves: an implementation has
 * to read {@code CommercialModel}, and the architecture test forbids that type outside this
 * package. Putting the interface in {@code inventory} and the implementations here would make
 * {@code commercialmodel} depend on {@code inventory}, which a second architecture rule
 * forbids. So the eight policies are this package's published API, and {@code inventory}
 * consults them — which is what doc 21's "owning module" column means in practice.
 *
 * <p>Note what is absent: no method returns or accepts a {@code CommercialModel}. A caller
 * hands over the opaque model code it stored and receives answers, never the model itself.
 * That is the difference between a policy and a getter with extra steps.
 */
public interface InventoryOwnershipPolicy {

    /**
     * Whether a project under this model must name an external developer.
     *
     * <p>Constraint C10 states the same rule in the database. Both exist deliberately: the
     * database is what makes it true, and this is what lets the API say why before the
     * insert fails.
     */
    boolean requiresDeveloper();

    /** Who the customer contracts with and pays. */
    SellerOfRecord sellerOfRecord();

    /**
     * Checks a project's developer against the model, returning the reason it is wrong or
     * empty when it is right.
     *
     * <p>Returns rather than throws: the caller owns the error vocabulary, and a policy that
     * threw {@code ApiException} would drag the web layer into a package that is meant to stay
     * free of it.
     */
    default java.util.Optional<String> rejectDeveloperAssignment(boolean developerPresent) {
        if (requiresDeveloper() && !developerPresent) {
            return java.util.Optional.of(
                    "A brokered project belongs to an external developer, so one must be named");
        }
        if (!requiresDeveloper() && developerPresent) {
            return java.util.Optional.of(
                    "An own-inventory project belongs to the tenant, so it cannot name a developer");
        }
        return java.util.Optional.empty();
    }
}
