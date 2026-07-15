package org.civiceconomy.integration.lightmanscurrency;

import java.time.Clock;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;
import java.util.function.Function;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.ConfirmTreasuryWithdrawal;
import org.civiceconomy.fiscal.ExternalTreasuryWithdrawal;
import org.civiceconomy.fiscal.FiscalAuthorization;
import org.civiceconomy.fiscal.FiscalCapability;
import org.civiceconomy.fiscal.FiscalServiceSession;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.fiscal.TreasuryWithdrawal;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredTreasuryWithdrawalOperation;

public final class TreasuryWithdrawalCoordinator {
    private final CivicDatabase database;
    private final ExternalTreasuryWithdrawals externalWithdrawals;
    private final FiscalAuthorization authorization;
    private final FiscalServiceSession session;
    private final Clock clock;

    private TreasuryWithdrawalCoordinator(
            CivicDatabase database,
            ExternalTreasuryWithdrawals externalWithdrawals,
            FiscalServiceSession session,
            Clock clock) {
        if (database == null || externalWithdrawals == null || session == null || clock == null) {
            throw new IllegalArgumentException("Treasury Withdrawal dependencies cannot be null");
        }
        this.database = database;
        this.externalWithdrawals = externalWithdrawals;
        this.authorization = new FiscalAuthorization(database);
        this.session = session;
        this.clock = clock;
    }

    static TreasuryWithdrawalCoordinator authorized(
            CivicDatabase database,
            ExternalTreasuryWithdrawals externalWithdrawals,
            FiscalServiceSession session,
            Clock clock) {
        return new TreasuryWithdrawalCoordinator(database, externalWithdrawals, session, clock);
    }

    public static TreasuryWithdrawalCoordinator live(
            CivicDatabase database,
            FiscalServiceSession session,
            Clock clock,
            ServerLevel level) {
        return new TreasuryWithdrawalCoordinator(
                database,
                withdrawal -> LightmansCurrencyTreasuryWithdrawals.live(level)
                        .apply(withdrawal),
                session,
                clock);
    }

    public static TreasuryWithdrawalCoordinator live(
            CivicDatabase database,
            FiscalServiceSession session,
            Clock clock,
            ServerLevel level,
            Function<UUID, ServerPlayer> players) {
        return new TreasuryWithdrawalCoordinator(
                database,
                withdrawal -> LightmansCurrencyTreasuryWithdrawals.live(level, players)
                        .apply(withdrawal),
                session,
                clock);
    }

    public TreasuryWithdrawal confirm(ConfirmTreasuryWithdrawal request) {
        TreasuryWithdrawal prepared = prepare(request);
        applyExternal(prepared);
        return commit(prepared);
    }

    public TreasuryWithdrawal prepare(ConfirmTreasuryWithdrawal request) {
        authorization.require(
                session,
                request.serviceIdentity(),
                FiscalCapability.WITHDRAW_CASH,
                request.sourceAccount());
        StoredTreasuryWithdrawalOperation operation = database.prepareTreasuryWithdrawal(
                java.util.UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.nationId().value(),
                request.sourceAccount().value(),
                request.actorPlayerId(),
                request.amount().minorUnits(),
                request.reason(),
                clock.millis());
        requirePayload(operation, request);
        return toWithdrawal(operation);
    }

    public void recoverAll() {
        for (TreasuryWithdrawal withdrawal : pending()) {
            applyExternal(withdrawal);
            commit(withdrawal);
        }
    }

    public List<TreasuryWithdrawal> pending() {
        return database.pendingTreasuryWithdrawalOperations(
                        session.serviceIdentity().value())
                .stream()
                .map(TreasuryWithdrawalCoordinator::toWithdrawal)
                .toList();
    }

    public void applyExternal(TreasuryWithdrawal withdrawal) {
        session.requireIdentity(withdrawal.serviceIdentity());
        if (withdrawal.state().equals("COMMITTED")) {
            return;
        }
        externalWithdrawals.apply(new ExternalTreasuryWithdrawal(
                withdrawal.withdrawalId(),
                withdrawal.sourceAccount(),
                withdrawal.actorPlayerId(),
                withdrawal.amount()));
    }

    public TreasuryWithdrawal commit(TreasuryWithdrawal withdrawal) {
        session.requireIdentity(withdrawal.serviceIdentity());
        return toWithdrawal(database.commitTreasuryWithdrawal(
                withdrawal.withdrawalId(), clock.millis()));
    }

    private static void requirePayload(
            StoredTreasuryWithdrawalOperation operation,
            ConfirmTreasuryWithdrawal request) {
        if (!operation.nationId().equals(request.nationId().value())
                || !operation.sourceAccount().equals(request.sourceAccount().value())
                || !operation.actorPlayerId().equals(request.actorPlayerId())
                || operation.amountMinorUnits() != request.amount().minorUnits()
                || !operation.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(
                    request.serviceIdentity(), request.requestId());
        }
    }

    private static TreasuryWithdrawal toWithdrawal(
            StoredTreasuryWithdrawalOperation operation) {
        return new TreasuryWithdrawal(
                operation.withdrawalId(),
                new ServiceIdentity(operation.serviceIdentity()),
                operation.requestId(),
                new NationId(operation.nationId()),
                new AccountId(operation.sourceAccount()),
                operation.actorPlayerId(),
                MoneyAmount.ofMinorUnits(operation.amountMinorUnits()),
                operation.reason(),
                operation.state(),
                java.time.Instant.ofEpochMilli(operation.preparedAtEpochMillis()),
                operation.committedAtEpochMillis() == null
                        ? null
                        : java.time.Instant.ofEpochMilli(
                                operation.committedAtEpochMillis()));
    }
}
