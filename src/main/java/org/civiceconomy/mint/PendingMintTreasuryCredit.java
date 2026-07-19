package org.civiceconomy.mint;

import java.util.UUID;

public record PendingMintTreasuryCredit(
        UUID operationId, UUID batchId, ExternalMintIssuance issuance)
        implements PendingMintIssuanceStep {
    public PendingMintTreasuryCredit {
        if (operationId == null
                || batchId == null
                || issuance == null
                || !operationId.equals(issuance.issuanceId())) {
            throw new IllegalArgumentException("Pending Mint Treasury credit is invalid");
        }
    }

    @Override
    public void apply(
            ExternalMintIssuances issuances, ExternalMintMaterialCustody custody) {
        issuances.apply(issuance);
    }
}
