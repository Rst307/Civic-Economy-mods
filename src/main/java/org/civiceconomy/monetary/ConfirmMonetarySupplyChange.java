package org.civiceconomy.monetary;

import org.civiceconomy.fiscal.ServiceIdentity;

public record ConfirmMonetarySupplyChange(
        ServiceIdentity serviceIdentity,
        String requestId,
        MonetarySupplyChange change,
        long amountMinorUnits,
        String externalReference,
        String reason) {
    public ConfirmMonetarySupplyChange {
        if (serviceIdentity == null || change == null) {
            throw new IllegalArgumentException("Monetary Supply change cannot contain null values");
        }
        if (requestId == null
                || requestId.isBlank()
                || externalReference == null
                || externalReference.isBlank()
                || reason == null
                || reason.isBlank()
                || amountMinorUnits <= 0L
                || (change != MonetarySupplyChange.ISSUANCE
                        && change != MonetarySupplyChange.PERMANENT_DESTRUCTION)) {
            throw new IllegalArgumentException("Monetary Supply change values are invalid");
        }
    }
}
