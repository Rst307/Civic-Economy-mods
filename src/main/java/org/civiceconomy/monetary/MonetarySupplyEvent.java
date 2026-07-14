package org.civiceconomy.monetary;

import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.ServiceIdentity;

public record MonetarySupplyEvent(
        UUID eventId,
        ServiceIdentity serviceIdentity,
        String requestId,
        MonetarySupplyChange change,
        MoneyAmount amount,
        String externalReference,
        String reason,
        Instant confirmedAt) {
    public MonetarySupplyEvent {
        if (eventId == null
                || serviceIdentity == null
                || change == null
                || amount == null
                || confirmedAt == null) {
            throw new IllegalArgumentException("Monetary Supply event cannot contain null values");
        }
        if (requestId == null
                || requestId.isBlank()
                || externalReference == null
                || externalReference.isBlank()
                || reason == null
                || reason.isBlank()
                || amount.minorUnits() <= 0L) {
            throw new IllegalArgumentException("Monetary Supply event values are invalid");
        }
    }
}
