package org.civiceconomy.mint;

import java.util.UUID;

public sealed interface PendingMintMaterialOperation
        permits PendingMintMaterialTake, PendingMintMaterialReturn {
    UUID batchId();

    void apply(ExternalMintMaterialCustody custody);
}
