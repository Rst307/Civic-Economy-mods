package org.civiceconomy.mint;

import java.util.UUID;

public sealed interface PendingMintIssuanceStep
        permits PendingMintTreasuryCredit, PendingMintMaterialConsumption {
    UUID operationId();

    UUID batchId();

    void apply(ExternalMintIssuances issuances, ExternalMintMaterialCustody custody);
}
