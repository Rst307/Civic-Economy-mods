package org.civiceconomy.fiscal;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.InactiveReservationException;
import org.civiceconomy.persistence.PendingReservationPaymentException;
import org.civiceconomy.persistence.StoredBudget;
import org.civiceconomy.persistence.StoredEscrow;
import org.civiceconomy.persistence.StoredEscrowExpiry;
import org.civiceconomy.persistence.StoredFiscalBill;
import org.civiceconomy.persistence.StoredLedgerEntry;
import org.civiceconomy.persistence.StoredReservation;
import org.civiceconomy.persistence.StoredReservationRelease;

public final class FiscalLedger {
    private final CivicDatabase database;
    private final AccountBalances accountBalances;
    private final Clock clock;
    private final FiscalAuthorization authorization;
    private final FiscalServiceSession session;
    private final ConcurrentHashMap<AccountId, Object> accountLocks = new ConcurrentHashMap<>();

    FiscalLedger(CivicDatabase database, AccountBalances accountBalances) {
        this(database, accountBalances, Clock.systemUTC(), null, null);
    }

    FiscalLedger(CivicDatabase database, AccountBalances accountBalances, Clock clock) {
        this(database, accountBalances, clock, null, null);
    }

    private FiscalLedger(
            CivicDatabase database,
            AccountBalances accountBalances,
            Clock clock,
            FiscalAuthorization authorization,
            FiscalServiceSession session) {
        this.database = database;
        this.accountBalances = accountBalances;
        this.clock = clock;
        this.authorization = authorization;
        this.session = session;
    }

    static FiscalLedger authorized(CivicDatabase database, AccountBalances accountBalances) {
        return new FiscalLedger(
                database,
                accountBalances,
                Clock.systemUTC(),
                new FiscalAuthorization(database),
                null);
    }

    public static FiscalLedger authorized(
            CivicDatabase database,
            AccountBalances accountBalances,
            FiscalServiceSession session) {
        java.util.Objects.requireNonNull(session, "Fiscal service session cannot be null");
        return new FiscalLedger(
                database,
                accountBalances,
                Clock.systemUTC(),
                new FiscalAuthorization(database),
                session);
    }

    public Reservation reserve(ReserveFunds request) {
        require(request.serviceIdentity(), FiscalCapability.RESERVE_FUNDS, request.sourceAccount());
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

    MoneyAmount reservedBalance(AccountId accountId) {
        return MoneyAmount.ofMinorUnits(database.activeReservedMinorUnits(accountId.value()));
    }

    public MoneyAmount reservedBalance(ServiceIdentity serviceIdentity, AccountId accountId) {
        require(serviceIdentity, FiscalCapability.READ_ACCOUNT, accountId);
        return reservedBalance(accountId);
    }

    java.util.List<LedgerEntry> ledgerEntries(AccountId accountId) {
        return database.ledgerEntries(accountId.value()).stream()
                .map(FiscalLedger::toLedgerEntry)
                .toList();
    }

    public java.util.List<LedgerEntry> ledgerEntries(
            ServiceIdentity serviceIdentity, AccountId accountId) {
        require(serviceIdentity, FiscalCapability.READ_ACCOUNT, accountId);
        return ledgerEntries(accountId);
    }

    public FiscalBill issueBill(IssueFiscalBill request) {
        require(request.serviceIdentity(), FiscalCapability.ISSUE_BILL, request.beneficiaryAccount());
        StoredFiscalBill existing = database.fiscalBill(
                request.serviceIdentity().value(), request.requestId());
        if (existing != null) {
            if (!existing.payerAccount().equals(request.payerAccount().value())
                    || !existing.beneficiaryAccount().equals(request.beneficiaryAccount().value())
                    || existing.amountMinorUnits() != request.amount().minorUnits()
                    || !existing.kind().equals(request.kind().name())
                    || !existing.purpose().equals(request.purpose())
                    || existing.dueAtEpochMillis() != request.dueAt().toEpochMilli()) {
                throw new IdempotencyConflictException(
                        request.serviceIdentity(), request.requestId());
            }
            return toFiscalBill(existing);
        }
        if (!request.dueAt().isAfter(clock.instant())) {
            throw new IllegalArgumentException("Fiscal Bill due date must be in the future");
        }
        return toFiscalBill(database.issueFiscalBill(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.payerAccount().value(),
                request.beneficiaryAccount().value(),
                request.amount().minorUnits(),
                request.kind().name(),
                request.purpose(),
                request.dueAt().toEpochMilli()));
    }

    public FiscalBill fundBill(FundFiscalBill request) {
        requireSessionIdentity(request.serviceIdentity());
        StoredFiscalBill bill = database.fiscalBill(request.billId());
        if (bill == null) {
            throw new IllegalArgumentException("Unknown Fiscal Bill " + request.billId());
        }
        AccountId payerAccount = new AccountId(bill.payerAccount());
        require(request.serviceIdentity(), FiscalCapability.FUND_BILL, payerAccount);
        StoredFiscalBill replay = database.fiscalBillFunding(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            if (!replay.billId().equals(request.billId())) {
                throw new IdempotencyConflictException(
                        request.serviceIdentity(), request.requestId());
            }
            return toFiscalBill(replay);
        }
        synchronized (accountLocks.computeIfAbsent(payerAccount, ignored -> new Object())) {
            replay = database.fiscalBillFunding(
                    request.serviceIdentity().value(), request.requestId());
            if (replay != null) {
                if (!replay.billId().equals(request.billId())) {
                    throw new IdempotencyConflictException(
                            request.serviceIdentity(), request.requestId());
                }
                return toFiscalBill(replay);
            }
            if (!"ISSUED".equals(bill.state())) {
                throw new IllegalStateException(
                        "Fiscal Bill is not awaiting funding: " + bill.state());
            }
            if (clock.millis() >= bill.dueAtEpochMillis()) {
                throw new IllegalStateException("Fiscal Bill is past due");
            }
            MoneyAmount amount = MoneyAmount.ofMinorUnits(bill.amountMinorUnits());
            MoneyAmount available = availableBalance(payerAccount);
            if (available.compareTo(amount) < 0) {
                throw new InsufficientAvailableBalanceException(
                        payerAccount, amount, available);
            }
            return toFiscalBill(database.fundFiscalBill(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    request.serviceIdentity().value(),
                    request.requestId(),
                    request.billId()));
        }
    }

    public Budget createBudget(CreateBudget request) {
        require(request.serviceIdentity(), FiscalCapability.MANAGE_BUDGET, request.sourceAccount());
        StoredBudget existing = database.budget(
                request.serviceIdentity().value(), request.requestId());
        if (existing != null) {
            if (!existing.sourceAccount().equals(request.sourceAccount().value())
                    || existing.amountMinorUnits() != request.amount().minorUnits()
                    || !existing.budgetCode().equals(request.budgetCode())
                    || !existing.purpose().equals(request.purpose())
                    || existing.expiresAtEpochMillis() != request.expiresAt().toEpochMilli()) {
                throw new IdempotencyConflictException(
                        request.serviceIdentity(), request.requestId());
            }
            return toBudget(existing);
        }
        if (!request.expiresAt().isAfter(clock.instant())) {
            throw new IllegalArgumentException("Budget expiry must be in the future");
        }
        return toBudget(database.createBudget(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.sourceAccount().value(),
                request.amount().minorUnits(),
                request.budgetCode(),
                request.purpose(),
                request.expiresAt().toEpochMilli()));
    }

    public Budget approveBudget(ApproveBudget request) {
        requireSessionIdentity(request.serviceIdentity());
        StoredBudget budget = database.budget(request.budgetId());
        if (budget == null) {
            throw new IllegalArgumentException("Unknown Budget " + request.budgetId());
        }
        AccountId sourceAccount = new AccountId(budget.sourceAccount());
        require(request.serviceIdentity(), FiscalCapability.MANAGE_BUDGET, sourceAccount);
        StoredBudget replay = database.budgetApproval(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            if (!replay.budgetId().equals(request.budgetId())) {
                throw new IdempotencyConflictException(
                        request.serviceIdentity(), request.requestId());
            }
            return toBudget(replay);
        }
        synchronized (accountLocks.computeIfAbsent(sourceAccount, ignored -> new Object())) {
            replay = database.budgetApproval(
                    request.serviceIdentity().value(), request.requestId());
            if (replay != null) {
                if (!replay.budgetId().equals(request.budgetId())) {
                    throw new IdempotencyConflictException(
                            request.serviceIdentity(), request.requestId());
                }
                return toBudget(replay);
            }
            if (!"DRAFT".equals(budget.state())) {
                throw new IllegalStateException("Budget is not a draft: " + budget.state());
            }
            if (clock.millis() >= budget.expiresAtEpochMillis()) {
                throw new IllegalStateException("Budget has expired before approval");
            }
            MoneyAmount amount = MoneyAmount.ofMinorUnits(budget.amountMinorUnits());
            MoneyAmount available = availableBalance(sourceAccount);
            if (available.compareTo(amount) < 0) {
                throw new InsufficientAvailableBalanceException(sourceAccount, amount, available);
            }
            return toBudget(database.approveBudget(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    request.serviceIdentity().value(),
                    request.requestId(),
                    request.budgetId()));
        }
    }

    public Escrow openEscrow(OpenEscrow request) {
        require(request.serviceIdentity(), FiscalCapability.MANAGE_ESCROW, request.sourceAccount());
        synchronized (accountLocks.computeIfAbsent(request.sourceAccount(), ignored -> new Object())) {
            StoredEscrow existing = database.escrow(
                    request.serviceIdentity().value(), request.requestId());
            if (existing != null) {
                if (!existing.sourceAccount().equals(request.sourceAccount().value())
                        || existing.amountMinorUnits() != request.amount().minorUnits()
                        || !existing.externalObjectId().equals(request.externalObjectId())
                        || !existing.purpose().equals(request.purpose())
                        || existing.expiresAtEpochMillis() != request.expiresAt().toEpochMilli()) {
                    throw new IdempotencyConflictException(
                            request.serviceIdentity(), request.requestId());
                }
                return toEscrow(existing);
            }
            if (!request.expiresAt().isAfter(clock.instant())) {
                throw new IllegalArgumentException("Escrow expiry must be in the future");
            }
            MoneyAmount available = availableBalance(request.sourceAccount());
            if (available.compareTo(request.amount()) < 0) {
                throw new InsufficientAvailableBalanceException(
                        request.sourceAccount(), request.amount(), available);
            }
            return toEscrow(database.openEscrow(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    request.serviceIdentity().value(),
                    request.requestId(),
                    request.sourceAccount().value(),
                    request.amount().minorUnits(),
                    request.externalObjectId(),
                    request.purpose(),
                    request.expiresAt().toEpochMilli()));
        }
    }

    Escrow escrow(UUID escrowId) {
        StoredEscrow stored = database.escrow(escrowId);
        if (stored == null) {
            throw new IllegalArgumentException("Unknown Escrow " + escrowId);
        }
        return toEscrow(stored);
    }

    public Escrow escrow(ServiceIdentity serviceIdentity, UUID escrowId) {
        requireSessionIdentity(serviceIdentity);
        StoredEscrow stored = database.escrow(escrowId);
        if (stored == null) {
            throw new IllegalArgumentException("Unknown Escrow " + escrowId);
        }
        AccountId sourceAccount = new AccountId(stored.sourceAccount());
        require(serviceIdentity, FiscalCapability.READ_ACCOUNT, sourceAccount);
        return toEscrow(stored);
    }

    public Escrow expireEscrow(ExpireEscrow request) {
        requireSessionIdentity(request.serviceIdentity());
        StoredEscrow escrow = database.escrow(request.escrowId());
        if (escrow == null) {
            throw new IllegalArgumentException("Unknown Escrow " + request.escrowId());
        }
        require(
                request.serviceIdentity(),
                FiscalCapability.MANAGE_ESCROW,
                new AccountId(escrow.sourceAccount()));
        StoredEscrowExpiry replay = database.escrowExpiry(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            if (!replay.escrowId().equals(request.escrowId())) {
                throw new IdempotencyConflictException(
                        request.serviceIdentity(), request.requestId());
            }
            return toEscrow(database.escrow(replay.escrowId()));
        }
        try {
            return toEscrow(database.expireEscrow(
                    UUID.randomUUID(),
                    request.serviceIdentity().value(),
                    request.requestId(),
                    request.escrowId(),
                    clock.millis()));
        } catch (PendingReservationPaymentException blocked) {
            throw new ReservationHasPendingPaymentException(blocked.reservationId());
        }
    }

    public ReservationRelease release(ReleaseReservation request) {
        requireSessionIdentity(request.serviceIdentity());
        if (authorization != null) {
            StoredReservation reservation = database.reservationRecord(request.reservationId());
            if (reservation == null) {
                throw new IllegalArgumentException("Unknown Reservation " + request.reservationId());
            }
            require(
                    request.serviceIdentity(),
                    FiscalCapability.RESERVE_FUNDS,
                    new AccountId(reservation.sourceAccount()));
        }
        StoredReservationRelease replay = database.reservationRelease(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            if (!replay.reservationId().equals(request.reservationId())
                    || !replay.reason().equals(request.reason())) {
                throw new IdempotencyConflictException(request.serviceIdentity(), request.requestId());
            }
            return toReservationRelease(replay);
        }
        try {
            return toReservationRelease(database.releaseReservation(
                    UUID.randomUUID(),
                    request.serviceIdentity().value(),
                    request.requestId(),
                    request.reservationId(),
                    request.reason(),
                    clock.millis()));
        } catch (PendingReservationPaymentException blocked) {
            throw new ReservationHasPendingPaymentException(blocked.reservationId());
        } catch (InactiveReservationException inactive) {
            throw new ReservationNotActiveException(inactive.reservationId(), inactive.state());
        }
    }

    MoneyAmount availableBalance(AccountId accountId) {
        return accountBalances.balance(accountId).minus(reservedBalance(accountId));
    }

    public MoneyAmount availableBalance(ServiceIdentity serviceIdentity, AccountId accountId) {
        require(serviceIdentity, FiscalCapability.READ_ACCOUNT, accountId);
        return availableBalance(accountId);
    }

    private static Reservation toReservation(StoredReservation stored) {
        return new Reservation(
                stored.reservationId(),
                new ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                new AccountId(stored.sourceAccount()),
                MoneyAmount.ofMinorUnits(stored.amountMinorUnits()),
                MoneyAmount.ofMinorUnits(stored.settledMinorUnits()),
                stored.purpose(),
                ReservationState.valueOf(stored.state()));
    }

    private static ReservationRelease toReservationRelease(StoredReservationRelease stored) {
        return new ReservationRelease(
                stored.releaseId(),
                new ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                stored.reservationId(),
                stored.reason(),
                stored.releasedAtEpochMillis());
    }

    private static Escrow toEscrow(StoredEscrow stored) {
        return new Escrow(
                stored.escrowId(),
                new ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                stored.reservationId(),
                new AccountId(stored.sourceAccount()),
                MoneyAmount.ofMinorUnits(stored.amountMinorUnits()),
                MoneyAmount.ofMinorUnits(stored.settledMinorUnits()),
                stored.externalObjectId(),
                stored.purpose(),
                java.time.Instant.ofEpochMilli(stored.expiresAtEpochMillis()),
                java.util.Optional.ofNullable(stored.requiredRecipientAccount()).map(AccountId::new),
                EscrowState.valueOf(stored.state()));
    }

    private static Budget toBudget(StoredBudget stored) {
        return new Budget(
                stored.budgetId(),
                new ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                new AccountId(stored.sourceAccount()),
                MoneyAmount.ofMinorUnits(stored.amountMinorUnits()),
                stored.budgetCode(),
                stored.purpose(),
                java.time.Instant.ofEpochMilli(stored.expiresAtEpochMillis()),
                java.util.Optional.ofNullable(stored.escrowId()),
                MoneyAmount.ofMinorUnits(stored.settledMinorUnits()),
                BudgetState.valueOf(stored.state()));
    }

    private static FiscalBill toFiscalBill(StoredFiscalBill stored) {
        return new FiscalBill(
                stored.billId(),
                new ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                new AccountId(stored.payerAccount()),
                new AccountId(stored.beneficiaryAccount()),
                MoneyAmount.ofMinorUnits(stored.amountMinorUnits()),
                FiscalBillKind.valueOf(stored.kind()),
                stored.purpose(),
                java.time.Instant.ofEpochMilli(stored.dueAtEpochMillis()),
                java.util.Optional.ofNullable(stored.escrowId()),
                MoneyAmount.ofMinorUnits(stored.settledMinorUnits()),
                FiscalBillState.valueOf(stored.state()));
    }

    private static LedgerEntry toLedgerEntry(StoredLedgerEntry stored) {
        return new LedgerEntry(
                stored.entryId(),
                stored.transactionId(),
                new AccountId(stored.account()),
                new AccountId(stored.counterpartyAccount()),
                MoneyAmount.ofMinorUnits(stored.amountMinorUnits()),
                LedgerDirection.valueOf(stored.direction()),
                PaymentKind.valueOf(stored.transactionKind()),
                java.time.Instant.ofEpochMilli(stored.recordedAtEpochMillis()));
    }

    private void require(
            ServiceIdentity serviceIdentity, FiscalCapability capability, AccountId accountId) {
        if (authorization != null) {
            requireSessionIdentity(serviceIdentity);
            authorization.require(serviceIdentity, capability, accountId);
        }
    }

    private void requireSessionIdentity(ServiceIdentity serviceIdentity) {
        if (session != null) {
            session.requireIdentity(serviceIdentity);
        }
    }
}
