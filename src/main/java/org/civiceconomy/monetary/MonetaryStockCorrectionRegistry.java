package org.civiceconomy.monetary;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredMonetaryStockCorrection;
import org.civiceconomy.persistence.StoredMonetarySupplyEvent;

public final class MonetaryStockCorrectionRegistry {
    private final CivicDatabase database;
    private final Clock clock;

    public MonetaryStockCorrectionRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException(
                    "Monetary Stock Correction dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public MonetaryStockCorrection correct(CorrectMonetaryStock request) {
        StoredMonetaryStockCorrection replay = database.monetaryStockCorrection(
                request.administratorIdentity(), request.requestId());
        if (replay != null) {
            requirePayload(replay, request);
            return toCorrection(replay);
        }
        return toCorrection(database.commitMintMonetaryStockCorrection(
                UUID.randomUUID(),
                request.administratorIdentity(),
                request.requestId(),
                request.incidentId(),
                request.evidenceReference(),
                request.reason(),
                clock.millis()));
    }

    public MoneyAmount cumulativeNetIssuance() {
        return MoneyAmount.ofMinorUnits(database.cumulativeNetIssuanceMinorUnits());
    }

    public MonetaryStockCorrection correction(UUID incidentId) {
        StoredMonetaryStockCorrection stored = database.monetaryStockCorrection(incidentId);
        if (stored == null) {
            throw new IllegalArgumentException(
                    "No Monetary Stock Correction is recorded for incident " + incidentId);
        }
        return toCorrection(stored);
    }

    public List<MonetarySupplyEvent> events() {
        return database.monetarySupplyEvents().stream()
                .map(MonetaryStockCorrectionRegistry::toEvent)
                .toList();
    }

    private static void requirePayload(
            StoredMonetaryStockCorrection stored, CorrectMonetaryStock request) {
        if (!stored.incidentId().equals(request.incidentId())
                || !stored.evidenceReference().equals(request.evidenceReference())
                || !stored.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(
                    new ServiceIdentity(request.administratorIdentity()), request.requestId());
        }
    }

    private MonetaryStockCorrection toCorrection(StoredMonetaryStockCorrection stored) {
        StoredMonetarySupplyEvent event = database.monetarySupplyEvent(
                stored.administratorIdentity(), stored.requestId());
        if (event == null || !event.eventId().equals(stored.eventId())) {
            throw new IllegalStateException(
                    "Monetary Stock Correction has no matching Monetary Supply event");
        }
        return new MonetaryStockCorrection(
                stored.correctionId(),
                stored.administratorIdentity(),
                stored.requestId(),
                stored.incidentId(),
                stored.operationId(),
                stored.batchId(),
                MoneyAmount.ofMinorUnits(stored.amountMinorUnits()),
                stored.evidenceReference(),
                stored.reason(),
                toEvent(event),
                Instant.ofEpochMilli(stored.correctedAtEpochMillis()));
    }

    private static MonetarySupplyEvent toEvent(StoredMonetarySupplyEvent stored) {
        return new MonetarySupplyEvent(
                stored.eventId(),
                new ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                MonetarySupplyChange.valueOf(stored.changeKind()),
                MoneyAmount.ofMinorUnits(stored.amountMinorUnits()),
                stored.externalReference(),
                stored.reason(),
                Instant.ofEpochMilli(stored.confirmedAtEpochMillis()));
    }
}
