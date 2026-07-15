package org.civiceconomy.integration.lightmanscurrency;

import java.time.Clock;
import net.minecraft.server.level.ServerLevel;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.FiscalAuthorization;
import org.civiceconomy.fiscal.FiscalCapability;
import org.civiceconomy.fiscal.FiscalServiceSession;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.monetary.ConfirmPermanentDestruction;
import org.civiceconomy.monetary.ExternalPermanentDestruction;
import org.civiceconomy.monetary.MonetarySupplyEvent;
import org.civiceconomy.monetary.MonetarySupplyLedger;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredMonetarySupplyEvent;
import org.civiceconomy.persistence.StoredPermanentDestructionOperation;

public final class PermanentDestructionCoordinator {
    private final CivicDatabase database;
    private final ExternalPermanentDestructions externalDestructions;
    private final FiscalAuthorization authorization;
    private final FiscalServiceSession session;
    private final Clock clock;

    private PermanentDestructionCoordinator(
            CivicDatabase database,
            ExternalPermanentDestructions externalDestructions,
            FiscalServiceSession session,
            Clock clock) {
        if (database == null || externalDestructions == null || session == null || clock == null) {
            throw new IllegalArgumentException("Permanent Destruction dependencies cannot be null");
        }
        this.database = database;
        this.externalDestructions = externalDestructions;
        this.authorization = new FiscalAuthorization(database);
        this.session = session;
        this.clock = clock;
    }

    static PermanentDestructionCoordinator authorized(
            CivicDatabase database,
            ExternalPermanentDestructions externalDestructions,
            FiscalServiceSession session,
            Clock clock) {
        return new PermanentDestructionCoordinator(database, externalDestructions, session, clock);
    }

    public static PermanentDestructionCoordinator live(
            CivicDatabase database,
            FiscalServiceSession session,
            Clock clock,
            ServerLevel level) {
        return new PermanentDestructionCoordinator(
                database,
                LightmansCurrencyPermanentDestructions.live(level),
                session,
                clock);
    }

    public MonetarySupplyEvent confirm(ConfirmPermanentDestruction request) {
        authorization.require(
                session,
                request.serviceIdentity(),
                FiscalCapability.PERMANENT_DESTRUCTION,
                request.sourceAccount());
        StoredPermanentDestructionOperation operation = database.preparePermanentDestruction(
                java.util.UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.sourceAccount().value(),
                request.amount().minorUnits(),
                request.operatorIdentity(),
                request.reason(),
                clock.millis());
        requirePayload(operation, request);
        return applyAndCommit(operation);
    }

    public void recoverAll() {
        for (StoredPermanentDestructionOperation operation :
                database.pendingPermanentDestructionOperations(
                        session.serviceIdentity().value())) {
            applyAndCommit(operation);
        }
    }

    private MonetarySupplyEvent applyAndCommit(StoredPermanentDestructionOperation operation) {
        externalDestructions.apply(new ExternalPermanentDestruction(
                operation.operationId(),
                new AccountId(operation.sourceAccount()),
                MoneyAmount.ofMinorUnits(operation.amountMinorUnits())));
        StoredMonetarySupplyEvent event = database.commitPermanentDestruction(
                operation.operationId(), clock.millis());
        return toEvent(event);
    }

    private static void requirePayload(
            StoredPermanentDestructionOperation operation,
            ConfirmPermanentDestruction request) {
        if (!operation.sourceAccount().equals(request.sourceAccount().value())
                || operation.amountMinorUnits() != request.amount().minorUnits()
                || !operation.operatorIdentity().equals(request.operatorIdentity())
                || !operation.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(
                    request.serviceIdentity(), request.requestId());
        }
    }

    private static MonetarySupplyEvent toEvent(StoredMonetarySupplyEvent stored) {
        return new MonetarySupplyEvent(
                stored.eventId(),
                new org.civiceconomy.fiscal.ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                org.civiceconomy.monetary.MonetarySupplyChange.valueOf(stored.changeKind()),
                MoneyAmount.ofMinorUnits(stored.amountMinorUnits()),
                stored.externalReference(),
                stored.reason(),
                java.time.Instant.ofEpochMilli(stored.confirmedAtEpochMillis()));
    }
}
