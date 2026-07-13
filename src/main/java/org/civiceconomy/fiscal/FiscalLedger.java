package org.civiceconomy.fiscal;

import java.util.concurrent.ConcurrentHashMap;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredReservation;

public final class FiscalLedger {
    private final CivicDatabase database;
    private final AccountBalances accountBalances;
    private final ConcurrentHashMap<AccountId, Object> accountLocks = new ConcurrentHashMap<>();

    public FiscalLedger(CivicDatabase database, AccountBalances accountBalances) {
        this.database = database;
        this.accountBalances = accountBalances;
    }

    public Reservation reserve(ReserveFunds request) {
        synchronized (accountLocks.computeIfAbsent(request.sourceAccount(), ignored -> new Object())) {
            StoredReservation existing = database.reservation(
                    request.serviceIdentity().value(), request.requestId());
            if (existing != null) {
                if (!existing.sourceAccount().equals(request.sourceAccount().value())
                        || existing.amountMinorUnits() != request.amount().minorUnits()
                        || !existing.purpose().equals(request.purpose())) {
                    throw new IdempotencyConflictException(request.serviceIdentity(), request.requestId());
                }
                return toReservation(existing);
            }
            MoneyAmount available = availableBalance(request.sourceAccount());
            if (available.compareTo(request.amount()) < 0) {
                throw new InsufficientAvailableBalanceException(request.sourceAccount(), request.amount(), available);
            }
            return toReservation(database.reserve(
                    request.serviceIdentity().value(),
                    request.requestId(),
                    request.sourceAccount().value(),
                    request.amount().minorUnits(),
                    request.purpose()));
        }
    }

    public MoneyAmount reservedBalance(AccountId accountId) {
        return MoneyAmount.ofMinorUnits(database.activeReservedMinorUnits(accountId.value()));
    }

    public MoneyAmount availableBalance(AccountId accountId) {
        return accountBalances.balance(accountId).minus(reservedBalance(accountId));
    }

    private static Reservation toReservation(StoredReservation stored) {
        return new Reservation(
                stored.reservationId(),
                new ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                new AccountId(stored.sourceAccount()),
                MoneyAmount.ofMinorUnits(stored.amountMinorUnits()),
                stored.purpose());
    }
}
