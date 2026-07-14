package org.civiceconomy.monetary;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredMonetarySupplyEvent;

public final class MonetarySupplyLedger {
    private final CivicDatabase database;
    private final MoneyAmount hardCap;
    private final Clock clock;

    public MonetarySupplyLedger(CivicDatabase database, MoneyAmount hardCap, Clock clock) {
        if (database == null || hardCap == null || clock == null) {
            throw new IllegalArgumentException(
                    "Monetary Supply Ledger dependencies cannot be null");
        }
        this.database = database;
        this.hardCap = hardCap;
        this.clock = clock;
    }

    public MonetarySupplyEvent confirm(ConfirmMonetarySupplyChange request) {
        StoredMonetarySupplyEvent replay = database.monetarySupplyEvent(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            requirePayload(replay, request);
            return toEvent(replay);
        }
        return toEvent(database.confirmMonetarySupplyChange(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.change().name(),
                request.amountMinorUnits(),
                request.externalReference(),
                request.reason(),
                clock.millis(),
                hardCap.minorUnits()));
    }

    public MoneyAmount cumulativeNetIssuance() {
        return MoneyAmount.ofMinorUnits(database.cumulativeNetIssuanceMinorUnits());
    }

    public MoneyAmount remainingHardCapSpace() {
        return hardCap.minus(cumulativeNetIssuance());
    }

    public List<MonetarySupplyEvent> events() {
        return database.monetarySupplyEvents().stream()
                .map(MonetarySupplyLedger::toEvent)
                .toList();
    }

    private static void requirePayload(
            StoredMonetarySupplyEvent stored, ConfirmMonetarySupplyChange request) {
        if (!stored.changeKind().equals(request.change().name())
                || stored.amountMinorUnits() != request.amountMinorUnits()
                || !stored.externalReference().equals(request.externalReference())
                || !stored.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(
                    request.serviceIdentity(), request.requestId());
        }
    }

    private static MonetarySupplyEvent toEvent(StoredMonetarySupplyEvent stored) {
        return new MonetarySupplyEvent(
                stored.eventId(),
                new org.civiceconomy.fiscal.ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                MonetarySupplyChange.valueOf(stored.changeKind()),
                MoneyAmount.ofMinorUnits(stored.amountMinorUnits()),
                stored.externalReference(),
                stored.reason(),
                Instant.ofEpochMilli(stored.confirmedAtEpochMillis()));
    }
}
