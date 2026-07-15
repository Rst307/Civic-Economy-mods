package org.civiceconomy.integration.lightmanscurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.ConfirmTreasuryWithdrawal;
import org.civiceconomy.fiscal.FiscalAuthorization;
import org.civiceconomy.fiscal.FiscalAccessDeniedException;
import org.civiceconomy.fiscal.FiscalCapability;
import org.civiceconomy.fiscal.FiscalServiceIdentityMismatchException;
import org.civiceconomy.fiscal.FiscalTestSessions;
import org.civiceconomy.fiscal.GrantFiscalCapability;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.RegisterFiscalService;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TreasuryWithdrawalCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ServiceIdentity SERVICE =
            new ServiceIdentity("treasury-withdrawal-service");
    private static final ServiceIdentity OTHER_SERVICE =
            new ServiceIdentity("other-treasury-withdrawal-service");
    private static final NationId NATION =
            new NationId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final AccountId TREASURY =
            new AccountId("nation:" + NATION.value() + ":treasury");
    private static final UUID ACTOR =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final NationId OTHER_NATION =
            new NationId(UUID.fromString("77777777-7777-7777-7777-777777777777"));
    private static final AccountId OTHER_TREASURY =
            new AccountId("nation:" + OTHER_NATION.value() + ":treasury");

    @TempDir Path temporaryDirectory;

    @Test
    void withdrawalAmountMustBePositive() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConfirmTreasuryWithdrawal(
                        SERVICE,
                        "negative-withdrawal",
                        NATION,
                        TREASURY,
                        ACTOR,
                        MoneyAmount.ofMinorUnits(-1L),
                        "Negative cash withdrawal"));
    }

    @Test
    void sourceMustBeTheExactNationalTreasuryForTheNation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConfirmTreasuryWithdrawal(
                        SERVICE,
                        "mismatched-national-treasury",
                        NATION,
                        new AccountId(
                                "nation:99999999-9999-9999-9999-999999999999:treasury"),
                        ACTOR,
                        MoneyAmount.ofMinorUnits(1L),
                        "Mismatched Nation and Treasury"));
    }

    @Test
    void committedReplayDoesNotReenterTheExternalWithdrawalAdapter() {
        AtomicLong externalCalls = new AtomicLong();
        ConfirmTreasuryWithdrawal request = new ConfirmTreasuryWithdrawal(
                SERVICE,
                "committed-replay",
                NATION,
                TREASURY,
                ACTOR,
                MoneyAmount.ofMinorUnits(100L),
                "Committed replay must stay inside Civic");
        try (CivicDatabase database = database()) {
            authorize(database);
            TreasuryWithdrawalCoordinator coordinator = coordinator(
                    database, ignored -> externalCalls.incrementAndGet());

            assertEquals("COMMITTED", coordinator.confirm(request).state());
            assertEquals("COMMITTED", coordinator.confirm(request).state());

            assertEquals(1L, externalCalls.get());
            assertEquals(1, database.treasuryWithdrawalOperations().size());
        }
    }

    @Test
    void identityAuthorityAndReplayPayloadFailBeforeExternalWithdrawal() {
        AtomicLong externalCalls = new AtomicLong();
        try (CivicDatabase database = database()) {
            authorize(database);
            TreasuryWithdrawalCoordinator coordinator = coordinator(
                    database, ignored -> externalCalls.incrementAndGet());

            assertThrows(
                    FiscalServiceIdentityMismatchException.class,
                    () -> coordinator.confirm(new ConfirmTreasuryWithdrawal(
                            new ServiceIdentity("impostor-service"),
                            "impostor-withdrawal",
                            NATION,
                            TREASURY,
                            ACTOR,
                            MoneyAmount.ofMinorUnits(100L),
                            "Must fail at session identity")));
            assertThrows(
                    FiscalAccessDeniedException.class,
                    () -> coordinator.confirm(new ConfirmTreasuryWithdrawal(
                            SERVICE,
                            "wrong-account-withdrawal",
                            OTHER_NATION,
                            OTHER_TREASURY,
                            ACTOR,
                            MoneyAmount.ofMinorUnits(100L),
                            "Must fail at exact account grant")));

            ConfirmTreasuryWithdrawal request = new ConfirmTreasuryWithdrawal(
                    SERVICE,
                    "authorized-withdrawal",
                    NATION,
                    TREASURY,
                    ACTOR,
                    MoneyAmount.ofMinorUnits(100L),
                    "Authorized exact-account withdrawal");
            coordinator.confirm(request);
            new FiscalAuthorization(database).grant(new GrantFiscalCapability(
                    new ServiceIdentity("test-admin"),
                    "grant-other-treasury-withdrawal",
                    SERVICE,
                    FiscalCapability.WITHDRAW_CASH,
                    OTHER_TREASURY,
                    "Permit replay scope-conflict test"));

            assertThrows(
                    org.civiceconomy.fiscal.IdempotencyConflictException.class,
                    () -> coordinator.confirm(new ConfirmTreasuryWithdrawal(
                            SERVICE,
                            request.requestId(),
                            OTHER_NATION,
                            OTHER_TREASURY,
                            ACTOR,
                            request.amount(),
                            request.reason())));
            assertThrows(
                    org.civiceconomy.fiscal.IdempotencyConflictException.class,
                    () -> coordinator.confirm(new ConfirmTreasuryWithdrawal(
                            SERVICE,
                            request.requestId(),
                            NATION,
                            TREASURY,
                            UUID.fromString("88888888-8888-8888-8888-888888888888"),
                            request.amount(),
                            request.reason())));
            assertThrows(
                    org.civiceconomy.fiscal.IdempotencyConflictException.class,
                    () -> coordinator.confirm(new ConfirmTreasuryWithdrawal(
                            SERVICE,
                            request.requestId(),
                            NATION,
                            TREASURY,
                            ACTOR,
                            MoneyAmount.ofMinorUnits(101L),
                            request.reason())));
            assertThrows(
                    org.civiceconomy.fiscal.IdempotencyConflictException.class,
                    () -> coordinator.confirm(new ConfirmTreasuryWithdrawal(
                            SERVICE,
                            request.requestId(),
                            NATION,
                            TREASURY,
                            ACTOR,
                            request.amount(),
                            "Changed purpose")));

            assertEquals(1L, externalCalls.get());
            assertEquals(1, database.treasuryWithdrawalOperations().size());
        }
    }

    @Test
    void restartAfterExternalCashDeliveryCommitsWithoutWithdrawingOrDeliveringTwice() {
        AtomicLong treasuryBalance = new AtomicLong(1_000L);
        AtomicLong deliveredCash = new AtomicLong();
        Set<UUID> applied = new HashSet<>();
        AtomicBoolean crashAfterFirstEffect = new AtomicBoolean(true);
        ConfirmTreasuryWithdrawal request = new ConfirmTreasuryWithdrawal(
                SERVICE,
                "cash-withdrawal-1",
                NATION,
                TREASURY,
                ACTOR,
                MoneyAmount.ofMinorUnits(600L),
                "Public works cash float");

        try (CivicDatabase database = database()) {
            authorize(database);
            TreasuryWithdrawalCoordinator coordinator = coordinator(
                    database,
                    withdrawal -> {
                        if (applied.add(withdrawal.withdrawalId())) {
                            treasuryBalance.addAndGet(-withdrawal.amount().minorUnits());
                            deliveredCash.addAndGet(withdrawal.amount().minorUnits());
                            if (crashAfterFirstEffect.getAndSet(false)) {
                                throw new IllegalStateException("simulated process death");
                            }
                        }
                    });

            assertThrows(IllegalStateException.class, () -> coordinator.confirm(request));
            assertEquals(400L, treasuryBalance.get());
            assertEquals(600L, deliveredCash.get());
            assertEquals(0L, database.cumulativeNetIssuanceMinorUnits());
            assertEquals(0, database.monetarySupplyEvents().size());
        }

        try (CivicDatabase database = database()) {
            TreasuryWithdrawalCoordinator recovery = coordinator(
                    database,
                    withdrawal -> {
                        if (applied.add(withdrawal.withdrawalId())) {
                            treasuryBalance.addAndGet(-withdrawal.amount().minorUnits());
                            deliveredCash.addAndGet(withdrawal.amount().minorUnits());
                        }
                    });

            recovery.recoverAll();
            var committed = recovery.confirm(request);

            assertEquals("COMMITTED", committed.state());
            assertEquals(400L, treasuryBalance.get());
            assertEquals(600L, deliveredCash.get());
            assertEquals(0L, database.cumulativeNetIssuanceMinorUnits());
            assertEquals(0, database.monetarySupplyEvents().size());
            assertEquals(1, database.treasuryWithdrawalOperations().size());
        }
    }

    @Test
    void recoveryOnlyAdvancesPreparedOperationsOwnedByTheVerifiedServiceSession() {
        try (CivicDatabase database = database()) {
            authorize(database);
            authorizeService(database, OTHER_SERVICE, "grant-other-withdrawal");
            ConfirmTreasuryWithdrawal owned = new ConfirmTreasuryWithdrawal(
                    SERVICE,
                    "owned-recovery",
                    NATION,
                    TREASURY,
                    ACTOR,
                    MoneyAmount.ofMinorUnits(100L),
                    "Owned recovery");
            ConfirmTreasuryWithdrawal other = new ConfirmTreasuryWithdrawal(
                    OTHER_SERVICE,
                    "other-recovery",
                    NATION,
                    TREASURY,
                    ACTOR,
                    MoneyAmount.ofMinorUnits(200L),
                    "Other recovery");
            assertThrows(
                    IllegalStateException.class,
                    () -> coordinator(database, SERVICE, ignored -> {
                        throw new IllegalStateException("leave owned PREPARED");
                    }).confirm(owned));
            assertThrows(
                    IllegalStateException.class,
                    () -> coordinator(database, OTHER_SERVICE, ignored -> {
                        throw new IllegalStateException("leave other PREPARED");
                    }).confirm(other));

            AtomicLong recoveredMinorUnits = new AtomicLong();
            coordinator(database, SERVICE, withdrawal -> recoveredMinorUnits.addAndGet(
                            withdrawal.amount().minorUnits()))
                    .recoverAll();

            assertEquals(100L, recoveredMinorUnits.get());
            assertEquals(
                    "COMMITTED",
                    database.treasuryWithdrawalOperation(
                                    SERVICE.value(), owned.requestId())
                            .state());
            assertEquals(
                    "PREPARED",
                    database.treasuryWithdrawalOperation(
                                    OTHER_SERVICE.value(), other.requestId())
                            .state());
        }
    }

    private TreasuryWithdrawalCoordinator coordinator(
            CivicDatabase database, ExternalTreasuryWithdrawals external) {
        return coordinator(database, SERVICE, external);
    }

    private TreasuryWithdrawalCoordinator coordinator(
            CivicDatabase database,
            ServiceIdentity serviceIdentity,
            ExternalTreasuryWithdrawals external) {
        return TreasuryWithdrawalCoordinator.authorized(
                database,
                external,
                FiscalTestSessions.open(
                        database, serviceIdentity, "civiceconomy-tests"),
                CLOCK);
    }

    private void authorize(CivicDatabase database) {
        database.registerNation(
                NATION.value(),
                "civiceconomy-tests",
                "register-withdrawal-nation",
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                NOW.toEpochMilli());
        FiscalAuthorization authorization = new FiscalAuthorization(database);
        authorizeService(database, SERVICE, "grant-treasury-withdrawal");
    }

    private void authorizeService(
            CivicDatabase database,
            ServiceIdentity serviceIdentity,
            String grantRequestId) {
        FiscalAuthorization authorization = new FiscalAuthorization(database);
        authorization.register(new RegisterFiscalService(
                serviceIdentity,
                "civiceconomy-tests",
                "Treasury Withdrawal test service"));
        authorization.grant(new GrantFiscalCapability(
                new ServiceIdentity("test-admin"),
                grantRequestId,
                serviceIdentity,
                FiscalCapability.WITHDRAW_CASH,
                TREASURY,
                "Test exact-account Treasury Withdrawal authority"));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("treasury-withdrawal.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("33333333-3333-3333-3333-333333333333"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
