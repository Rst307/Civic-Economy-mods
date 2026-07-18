package org.civiceconomy.production;

@FunctionalInterface
public interface ProductionInventoryExportSource {
    /**
     * Performs or observes one server-authoritative export and returns the exact stack
     * that actually crossed the trusted boundary. It must not accept an item identity
     * from the request because that identity is derived from the live server inventory.
     */
    ProductionStack export(ProductionInventoryExportRequest request);
}
