package org.civiceconomy.production;

import java.util.UUID;

@FunctionalInterface
public interface ProductionInventoryExportLineageSource {
    ProductionInventoryExportLineage lineage(UUID exportId);
}
