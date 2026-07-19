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
import org.civiceconomy.monetary.PermanentDestructionOperation;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredMonetarySupplyEvent;
import org.civiceconomy.persistence.StoredPermanentDestructionOperation;

public final class PermanentDestructionCoordinator {
    private final CivicDatabase database;
    private final ExternalPermanentDestructions externalDestructions;
    private final FiscalAuthorization authorization;
    private final FiscalServiceSession session;
    private final Clock clock;
    private final PermanentDestructionProgressObserver progressObserver;

    private PermanentDestructionCoordinator(
            CivicDatabase database,
            ExternalPermanentDestructions externalDestructions,
            FiscalServiceSession session,
            Clock clock) {
        this(
                database,
                externalDestructions,
                session,
                clock,
                PermanentDestructionProgressObserver.NONE);
    }

    private PermanentDestructionCoordinator(
            CivicDatabase database,
            ExternalPermanentDestructions externalDestructions,
            FiscalServiceSession session,
            Clock clock,
            PermanentDestructionProgressObserver progressObserver) {
        if (database == null
                || externalDestructions == null
                || session == null
                || clock == null
                || progressObserver == null) {
            throw new IllegalArgumentException("Permanent Destruction dependencies cannot be null");
        }
        this.database = database;
        this.externalDestructions = externalDestructions;
        this.authorization = new FiscalAuthorization(database);
        this.session = session;
        this.clock = clock;
        this.progressObserver = progressObserver;
    }

    static PermanentDestructionCoordinator authorized(
            CivicDatabase database,
            ExternalPermanentDestructions externalDestructions,
            FiscalServiceSession session,
            Clock clock) {
        return new PermanentDestructionCoordinator(database, externalDestructions, session, clock);
    }

    static PermanentDestructionCoordinator authorized(
            CivicDatabase database,
            ExternalPermanentDestructions externalDestructions,
            FiscalServiceSession session,
            Clock clock,
            PermanentDestructionProgressObserver progressObserver) {
        return new PermanentDestructionCoordinator(
                database,
                externalDestructions,
                session,
                clock,
                progressObserver);
    }

    public static PermanentDestructionCoordinator live(
            CivicDatabase database,
            FiscalServiceSession session,
            Clock clock,
            ServerLevel level) {
        return live(
                database,
                session,
                clock,
                level,
                PermanentDestructionProgressObserver.NONE);
    }

    public static PermanentDestructionCoordinator live(
            CivicDatabase database,
            FiscalServiceSession session,
            Clock clock,
            ServerLevel level,
            PermanentDestructionProgressObserver progressObserver) {
        return new PermanentDestructionCoordinator(
                database,
                LightmansCurrencyPermanentDestructions.live(level),
                session,
                clock,
                progressObserver);
    }

    public MonetarySupplyEvent confirm(ConfirmPermanentDestruction request) {
        PermanentDestructionOperation operation = prepare(request);
        applyExternal(operation);
        recordExternalApplied(operation);
        return commit(operation);
    }

    public PermanentDestructionOperation prepare(ConfirmPermanentDestruction request) {
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
        return toOperation(operation);
    }

    public void recoverAll() {
        for (PermanentDestructionOperation operation : pending()) {
            applyExternal(operation);
            recordExternalApplied(operation);
            commit(operation);
        }
    }

    public java.util.List<PermanentDestructionOperation> pending() {
        return database.pendingPermanentDestructionOperations(
                        session.serviceIdentity().value())
                .stream()
                .map(PermanentDestructionCoordinator::toOperation)
                .toList();
    }

    public void applyExternal(PermanentDestructionOperation operation) {
        session.requireIdentity(operation.serviceIdentity());
        if (operation.state().equals("COMMITTED")) {
            return;
        }
        ExternalPermanentDestruction destruction = new ExternalPermanentDestruction(
                operation.operationId(),
                operation.sourceAccount(),
                operation.amount());
        externalDestructions.apply(destruction);
        progressObserver.afterExternalApplied(destruction);
    }

    public PermanentDestructionOperation recordExternalApplied(
            PermanentDestructionOperation operation) {
        session.requireIdentity(operation.serviceIdentity());
        StoredPermanentDestructionOperation recorded =
                database.markPermanentDestructionExternalApplied(
                operation.operationId(), clock.millis());
        progressObserver.afterExternalRecorded(new ExternalPermanentDestruction(
                operation.operationId(), operation.sourceAccount(), operation.amount()));
        return toOperation(recorded);
    }

    public MonetarySupplyEvent commit(PermanentDestructionOperation operation) {
        session.requireIdentity(operation.serviceIdentity());
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

    private static PermanentDestructionOperation toOperation(
            StoredPermanentDestructionOperation stored) {
        return new PermanentDestructionOperation(
                stored.operationId(),
                new org.civiceconomy.fiscal.ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                new AccountId(stored.sourceAccount()),
                MoneyAmount.ofMinorUnits(stored.amountMinorUnits()),
                stored.operatorIdentity(),
                stored.reason(),
                stored.state(),
                java.time.Instant.ofEpochMilli(stored.preparedAtEpochMillis()),
                stored.externalAppliedAtEpochMillis() == null
                        ? null
                        : java.time.Instant.ofEpochMilli(
                                stored.externalAppliedAtEpochMillis()),
                stored.committedAtEpochMillis() == null
                        ? null
                        : java.time.Instant.ofEpochMilli(stored.committedAtEpochMillis()));
    }
}
