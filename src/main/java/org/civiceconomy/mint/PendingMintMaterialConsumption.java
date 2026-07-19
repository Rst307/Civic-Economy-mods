package org.civiceconomy.mint;

import java.util.UUID;

public record PendingMintMaterialConsumption(
        UUID operationId, UUID batchId, MintMaterialCustodyConsumption consumption)
        implements PendingMintIssuanceStep {
    public PendingMintMaterialConsumption {
        if (operationId == null
                || batchId == null
                || consumption == null
                || !operationId.equals(consumption.operationId())
                || !batchId.equals(consumption.batchId())) {
            throw new IllegalArgumentException("Pending Mint material consumption is invalid");
        }
    }

    @Override
    public void apply(
            ExternalMintIssuances issuances, ExternalMintMaterialCustody custody) {
        custody.consume(consumption);
    }
}
