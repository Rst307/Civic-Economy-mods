package org.civiceconomy.territory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.FailurePoint;
import org.civiceconomy.fiscal.FiscalAuthorization;
import org.civiceconomy.fiscal.FiscalLedger;
import org.civiceconomy.fiscal.FiscalTestSessions;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.PaymentCoordinator;
import org.civiceconomy.fiscal.ReserveFunds;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.fiscal.SettleReservation;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerritoryClaimPermitCompensationCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-07-14T14:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final NationId NATION_ID = new NationId(
            UUID.fromString("88478276-bd89-4a3e-b387-c2525393cc2e"));
    private static final UUID TEAM_ID =
            UUID.fromString("7b19c243-8a76-46ae-97f4-b64efdf0dcce");
    private static final UUID ACTOR_ID =
            UUID.fromString("ed17b888-ab37-42b9-b35f-f32ba4f4f15b");
    private static final AccountId TREASURY =
            new AccountId("nation:" + NATION_ID.value() + ":treasury");
    private static final AccountId CLEARING =
            new AccountId("system:territory:prepayment-clearing");

    @TempDir
    Path temporaryDirectory;

    @Test
    void cancellationReturnsClearingFundsExactlyOnceAndSurvivesReplay() {
        Map<AccountId, Long> balances = new HashMap<>();
        balances.put(TREASURY, 1_000L);
        balances.put(CLEARING, 0L);
        Set<UUID> externallyApplied = new HashSet<>();

        try (CivicDatabase database = database()) {
            database.registerNation(
                    NATION_ID.value(), "permit-compensation-test", "register", TEAM_ID, NOW.toEpochMilli());
            ServiceIdentity service = TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY;
            new TerritoryFiscalServiceProvisioner(new FiscalAuthorization(database))
                    .ensureAuthorized(TREASURY);
            var session = FiscalTestSessions.open(database, service, "civiceconomy");
            FiscalLedger ledger = FiscalLedger.authorized(
                    database,
                    account -> MoneyAmount.ofMinorUnits(balances.getOrDefault(account, 0L)),
                    session);
            PaymentCoordinator payments = PaymentCoordinator.authorized(
                    database,
                    payment -> {
                        if (!externallyApplied.add(payment.transactionId())) {
                            return;
                        }
                        balances.compute(
                                payment.sourceAccount(),
                                (ignored, balance) -> Math.subtractExact(
                                        balance, payment.amount().minorUnits()));
                        balances.merge(
                                payment.recipientAccount(),
                                payment.amount().minorUnits(),
                                Math::addExact);
                    },
                    session);
            var reservation = ledger.reserve(new ReserveFunds(
                    service,
                    "reserve-cancellable-permit",
                    TREASURY,
                    MoneyAmount.ofMinorUnits(250L),
                    "Cancellable Territory Claim prepayment"));
            var payment = payments.settle(
                    new SettleReservation(
                            service,
                            "pay-cancellable-permit",
                            reservation.reservationId(),
                            CLEARING,
                            MoneyAmount.ofMinorUnits(250L)),
                    FailurePoint.NONE);
            TerritoryClaimPermitRegistry permits = new TerritoryClaimPermitRegistry(
                    database,
                    new CommittedTerritoryPrepaymentVerifier(database, CLEARING),
                    CLOCK);
            TerritoryClaimPermit permit = permits.issue(new IssueTerritoryClaimPermit(
                    service,
                    "issue-cancellable-permit",
                    NATION_ID,
                    TEAM_ID,
                    ACTOR_ID,
                    "minecraft:overworld",
                    31,
                    -4,
                    17,
                    17,
                    250L,
                    payment.transactionId(),
                    NOW.plusSeconds(120L)));
            TerritoryClaimPermitCompensationCoordinator compensation =
                    new TerritoryClaimPermitCompensationCoordinator(
                            database, payments, permits, service, CLOCK);
            CancelTerritoryClaimPermit request = new CancelTerritoryClaimPermit(
                    "cancel-cancellable-permit",
                    permit.permitId(),
                    ACTOR_ID,
                    "Player cancelled before claiming");

            TerritoryClaimPermit cancelled = compensation.cancel(request);

            assertEquals(TerritoryClaimPermitState.CANCELLED, cancelled.state());
            assertEquals(cancelled, compensation.cancel(request));
            assertEquals(1_000L, balances.get(TREASURY));
            assertEquals(0L, balances.get(CLEARING));
            assertEquals(
                    250L,
                    database.paymentTransaction(payment.transactionId()).refundedMinorUnits());
        }
    }

    @Test
    void cancellationRecoveryBlocksConsumptionAndDoesNotRepeatAmbiguousRefund() {
        Map<AccountId, Long> balances = new HashMap<>();
        balances.put(TREASURY, 1_000L);
        balances.put(CLEARING, 0L);
        Set<UUID> externallyApplied = new HashSet<>();
        AtomicBoolean failAfterRefundApplication = new AtomicBoolean(true);
        CancelTerritoryClaimPermit cancellation;
        UUID permitId;

        try (CivicDatabase database = database()) {
            database.registerNation(
                    NATION_ID.value(), "permit-compensation-test", "register", TEAM_ID, NOW.toEpochMilli());
            ServiceIdentity service = TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY;
            new TerritoryFiscalServiceProvisioner(new FiscalAuthorization(database))
                    .ensureAuthorized(TREASURY);
            var session = FiscalTestSessions.open(database, service, "civiceconomy");
            FiscalLedger ledger = FiscalLedger.authorized(
                    database,
                    account -> MoneyAmount.ofMinorUnits(balances.getOrDefault(account, 0L)),
                    session);
            PaymentCoordinator payments = PaymentCoordinator.authorized(
                    database,
                    payment -> {
                        if (!externallyApplied.add(payment.transactionId())) {
                            return;
                        }
                        balances.compute(
                                payment.sourceAccount(),
                                (ignored, balance) -> Math.subtractExact(
                                        balance, payment.amount().minorUnits()));
                        balances.merge(
                                payment.recipientAccount(),
                                payment.amount().minorUnits(),
                                Math::addExact);
                        if (payment.sourceAccount().equals(CLEARING)
                                && failAfterRefundApplication.getAndSet(false)) {
                            throw new IllegalStateException("ambiguous Permit refund");
                        }
                    },
                    session);
            var reservation = ledger.reserve(new ReserveFunds(
                    service,
                    "reserve-recoverable-permit",
                    TREASURY,
                    MoneyAmount.ofMinorUnits(250L),
                    "Recoverable Territory Claim prepayment"));
            var payment = payments.settle(
                    new SettleReservation(
                            service,
                            "pay-recoverable-permit",
                            reservation.reservationId(),
                            CLEARING,
                            MoneyAmount.ofMinorUnits(250L)),
                    FailurePoint.NONE);
            TerritoryClaimPermitRegistry permits = new TerritoryClaimPermitRegistry(
                    database,
                    new CommittedTerritoryPrepaymentVerifier(database, CLEARING),
                    CLOCK);
            TerritoryClaimPermit permit = permits.issue(new IssueTerritoryClaimPermit(
                    service,
                    "issue-recoverable-permit",
                    NATION_ID,
                    TEAM_ID,
                    ACTOR_ID,
                    "minecraft:overworld",
                    32,
                    -4,
                    17,
                    17,
                    250L,
                    payment.transactionId(),
                    NOW.plusSeconds(120L)));
            permitId = permit.permitId();
            cancellation = new CancelTerritoryClaimPermit(
                    "cancel-recoverable-permit",
                    permitId,
                    ACTOR_ID,
                    "Recover cancellation after ambiguous LC refund");
            TerritoryClaimPermitCompensationCoordinator compensation =
                    new TerritoryClaimPermitCompensationCoordinator(
                            database, payments, permits, service, CLOCK);

            assertThrows(IllegalStateException.class, () -> compensation.cancel(cancellation));
            assertEquals(1_000L, balances.get(TREASURY));
            assertEquals(0L, balances.get(CLEARING));
            assertThrows(
                    IllegalStateException.class,
                    () -> permits.consume(new ConsumeTerritoryClaimPermit(
                            service,
                            "consume-during-compensation",
                            permitId,
                            NATION_ID,
                            TEAM_ID,
                            ACTOR_ID,
                            "minecraft:overworld",
                            32,
                            -4)));
        }

        try (CivicDatabase reopened = database()) {
            ServiceIdentity service = TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY;
            var session = FiscalTestSessions.open(reopened, service, "civiceconomy");
            PaymentCoordinator payments = PaymentCoordinator.authorized(
                    reopened,
                    payment -> {
                        if (!externallyApplied.add(payment.transactionId())) {
                            return;
                        }
                        balances.compute(
                                payment.sourceAccount(),
                                (ignored, balance) -> Math.subtractExact(
                                        balance, payment.amount().minorUnits()));
                        balances.merge(
                                payment.recipientAccount(),
                                payment.amount().minorUnits(),
                                Math::addExact);
                    },
                    session);
            TerritoryClaimPermitRegistry permits = new TerritoryClaimPermitRegistry(
                    reopened,
                    new CommittedTerritoryPrepaymentVerifier(reopened, CLEARING),
                    CLOCK);

            TerritoryClaimPermit recovered = new TerritoryClaimPermitCompensationCoordinator(
                            reopened, payments, permits, service, CLOCK)
                    .cancel(cancellation);

            assertEquals(TerritoryClaimPermitState.CANCELLED, recovered.state());
            assertEquals(permitId, recovered.permitId());
            assertEquals(1_000L, balances.get(TREASURY));
            assertEquals(0L, balances.get(CLEARING));
        }
    }

    @Test
    void expiryReturnsClearingFundsAndFinalizesPermitExactlyOnce() {
        Map<AccountId, Long> balances = new HashMap<>();
        balances.put(TREASURY, 1_000L);
        balances.put(CLEARING, 0L);
        Set<UUID> externallyApplied = new HashSet<>();

        try (CivicDatabase database = database()) {
            database.registerNation(
                    NATION_ID.value(), "permit-compensation-test", "register", TEAM_ID, NOW.toEpochMilli());
            ServiceIdentity service = TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY;
            new TerritoryFiscalServiceProvisioner(new FiscalAuthorization(database))
                    .ensureAuthorized(TREASURY);
            var session = FiscalTestSessions.open(database, service, "civiceconomy");
            FiscalLedger ledger = FiscalLedger.authorized(
                    database,
                    account -> MoneyAmount.ofMinorUnits(balances.getOrDefault(account, 0L)),
                    session);
            PaymentCoordinator payments = PaymentCoordinator.authorized(
                    database,
                    payment -> {
                        if (!externallyApplied.add(payment.transactionId())) {
                            return;
                        }
                        balances.compute(
                                payment.sourceAccount(),
                                (ignored, balance) -> Math.subtractExact(
                                        balance, payment.amount().minorUnits()));
                        balances.merge(
                                payment.recipientAccount(),
                                payment.amount().minorUnits(),
                                Math::addExact);
                    },
                    session);
            var reservation = ledger.reserve(new ReserveFunds(
                    service,
                    "reserve-expiring-permit",
                    TREASURY,
                    MoneyAmount.ofMinorUnits(250L),
                    "Expiring Territory Claim prepayment"));
            var payment = payments.settle(
                    new SettleReservation(
                            service,
                            "pay-expiring-permit",
                            reservation.reservationId(),
                            CLEARING,
                            MoneyAmount.ofMinorUnits(250L)),
                    FailurePoint.NONE);
            TerritoryClaimPermitRegistry permits = new TerritoryClaimPermitRegistry(
                    database,
                    new CommittedTerritoryPrepaymentVerifier(database, CLEARING),
                    CLOCK);
            TerritoryClaimPermit permit = permits.issue(new IssueTerritoryClaimPermit(
                    service,
                    "issue-expiring-permit",
                    NATION_ID,
                    TEAM_ID,
                    ACTOR_ID,
                    "minecraft:overworld",
                    33,
                    -4,
                    17,
                    17,
                    250L,
                    payment.transactionId(),
                    NOW.plusSeconds(120L)));
            TerritoryClaimPermitCompensationCoordinator expiry =
                    new TerritoryClaimPermitCompensationCoordinator(
                            database,
                            payments,
                            permits,
                            service,
                            Clock.fixed(NOW.plusSeconds(120L), ZoneOffset.UTC));

            var expired = expiry.expireDue();

            assertEquals(1, expired.size());
            assertEquals(permit.permitId(), expired.getFirst().permitId());
            assertEquals(TerritoryClaimPermitState.EXPIRED, expired.getFirst().state());
            assertEquals(java.util.List.of(), expiry.expireDue());
            assertEquals(1_000L, balances.get(TREASURY));
            assertEquals(0L, balances.get(CLEARING));
        }
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("territory-permit-compensation.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("1ae30233-b861-4c8b-95ae-a478e50d1ed1"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
