package org.civiceconomy.monetary;

import java.util.UUID;

public record CorrectMonetaryStock(
        String administratorIdentity,
        String requestId,
        UUID incidentId,
        String evidenceReference,
        String reason) {
    public CorrectMonetaryStock {
        if (administratorIdentity == null
                || administratorIdentity.isBlank()
                || requestId == null
                || requestId.isBlank()
                || incidentId == null
                || evidenceReference == null
                || evidenceReference.isBlank()
                || reason == null
                || reason.isBlank()) {
            throw new IllegalArgumentException("Monetary Stock Correction values are invalid");
        }
    }
}
