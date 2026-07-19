package org.civiceconomy.monetary;

import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.fiscal.MoneyAmount;

public record MonetaryStockCorrection(
        UUID correctionId,
        String administratorIdentity,
        String requestId,
        UUID incidentId,
        UUID operationId,
        UUID batchId,
        MoneyAmount amount,
        String evidenceReference,
        String reason,
        MonetarySupplyEvent event,
        Instant correctedAt) {
    public MonetaryStockCorrection {
        if (correctionId == null
                || administratorIdentity == null
                || administratorIdentity.isBlank()
                || requestId == null
                || requestId.isBlank()
                || incidentId == null
                || operationId == null
                || batchId == null
                || amount == null
                || amount.minorUnits() <= 0L
                || evidenceReference == null
                || evidenceReference.isBlank()
                || reason == null
                || reason.isBlank()
                || event == null
                || event.change() != MonetarySupplyChange.STOCK_CORRECTION_INCREASE
                || !amount.equals(event.amount())
                || correctedAt == null) {
            throw new IllegalArgumentException("Monetary Stock Correction is invalid");
        }
    }
}
