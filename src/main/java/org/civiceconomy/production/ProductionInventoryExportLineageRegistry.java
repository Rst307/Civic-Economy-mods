package org.civiceconomy.production;

import java.util.UUID;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredProductionInventoryExportLineage;

/** Adapter that exposes only durable export-to-Receipt lineage facts. */
public final class ProductionInventoryExportLineageRegistry
        implements ProductionInventoryExportLineageSource {
    private final CivicDatabase database;

    public ProductionInventoryExportLineageRegistry(CivicDatabase database) {
        if (database == null) {
            throw new IllegalArgumentException("Production export lineage database is required");
        }
        this.database = database;
    }

    @Override
    public ProductionInventoryExportLineage lineage(UUID exportId) {
        StoredProductionInventoryExportLineage stored =
                database.productionInventoryExportLineage(exportId);
        if (stored == null) {
            return null;
        }
        return new ProductionInventoryExportLineage(
                stored.exportId(), stored.sourceReceiptIds());
    }
}
