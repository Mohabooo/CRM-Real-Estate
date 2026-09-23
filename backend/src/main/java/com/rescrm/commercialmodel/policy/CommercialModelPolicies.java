package com.rescrm.commercialmodel.policy;

import com.rescrm.commercialmodel.CommercialModel;
import com.rescrm.commercialmodel.CommercialModelCodes;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Resolves a stored commercial-model code to the policies that govern it.
 *
 * <p>This is the single place where a model code becomes behaviour, and it is the reason the
 * rest of the application can stay model-agnostic. Everywhere else the code is an opaque
 * string: a module holds it, persists it, hands it back here, and receives an answer. Doc 28
 * section 3 forbids any other module from reading the enum precisely so that a {@code switch}
 * on the model cannot appear somewhere nobody thinks to look.
 *
 * <p>The map is exhaustive by construction. Adding a third commercial model would fail here
 * immediately rather than silently defaulting to one of the existing two — the failure mode
 * that makes "we'll handle the new case later" into a bug that ships.
 */
@Component
public class CommercialModelPolicies {

    private final Map<CommercialModel, InventoryOwnershipPolicy> inventoryOwnership =
            new EnumMap<>(CommercialModel.class);
    private final Map<CommercialModel, CollectionPolicy> collection =
            new EnumMap<>(CommercialModel.class);

    public CommercialModelPolicies() {
        inventoryOwnership.put(CommercialModel.OWN_INVENTORY, new OwnInventoryOwnership());
        inventoryOwnership.put(CommercialModel.BROKERED_INVENTORY,
                new BrokeredInventoryOwnership());

        collection.put(CommercialModel.OWN_INVENTORY, new OwnInventoryCollection());
        collection.put(CommercialModel.BROKERED_INVENTORY, new BrokeredInventoryCollection());

        requireComplete("InventoryOwnershipPolicy", inventoryOwnership);
        requireComplete("CollectionPolicy", collection);
    }

    private static void requireComplete(String policy, Map<CommercialModel, ?> resolved) {
        for (CommercialModel model : CommercialModel.values()) {
            if (!resolved.containsKey(model)) {
                throw new IllegalStateException("No " + policy + " for commercial model "
                        + model + ". Every model must resolve every policy; a missing entry "
                        + "would otherwise surface as wrong behaviour at run time.");
            }
        }
    }

    /**
     * @param modelCode the stored code, e.g. {@code own_inventory}
     * @throws IllegalArgumentException naming the accepted values, for a usable API error
     */
    public InventoryOwnershipPolicy inventoryOwnership(String modelCode) {
        return inventoryOwnership.get(resolve(modelCode));
    }

    /**
     * @param modelCode the stored code, e.g. {@code brokered_inventory}
     * @throws IllegalArgumentException naming the accepted values, for a usable API error
     */
    public CollectionPolicy collection(String modelCode) {
        return collection.get(resolve(modelCode));
    }

    private static CommercialModel resolve(String modelCode) {
        CommercialModelCodes.requireValid(modelCode);
        for (CommercialModel model : CommercialModel.values()) {
            if (model.name().equalsIgnoreCase(modelCode)) {
                return model;
            }
        }
        // Unreachable: requireValid accepts exactly the codes this loop matches. Stated
        // rather than left as a silent null, because the two would drift apart in silence.
        throw new IllegalStateException("Unresolvable commercial model code '" + modelCode + "'");
    }
}
