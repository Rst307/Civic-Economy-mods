package org.civiceconomy.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FiscalAuthorizationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void registeredServiceHasNoWriteAuthorityByDefault() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        ServiceIdentity publicWorks = new ServiceIdentity("public-works");

        try (CivicDatabase database = database()) {
            FiscalAuthorization authorization = new FiscalAuthorization(database);
            authorization.register(new RegisterFiscalService(
                    publicWorks, "publicworksmod", "Public Works integration"));
            FiscalLedger ledger = FiscalLedger.authorized(
                    database, ignored -> MoneyAmount.ofMinorUnits(1_000));

            assertThrows(
                    FiscalAccessDeniedException.class,
                    () -> ledger.reserve(new ReserveFunds(
                            publicWorks,
                            "unauthorized-reservation",
                            treasury,
                            MoneyAmount.ofMinorUnits(300),
                            "Unauthorized bridge contract")));
            assertEquals(MoneyAmount.ZERO, ledger.reservedBalance(treasury));
        }
    }

    @Test
    void exactCapabilityGrantSurvivesReopenAndPermitsThatAccount() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        ServiceIdentity publicWorks = new ServiceIdentity("public-works");
        Path databaseFile = temporaryDirectory.resolve("persisted-authorization.sqlite3");

        try (CivicDatabase database = CivicDatabase.open(databaseFile, identity())) {
            FiscalAuthorization authorization = new FiscalAuthorization(database);
            authorization.register(new RegisterFiscalService(
                    publicWorks, "publicworksmod", "Public Works integration"));
            authorization.grant(new GrantFiscalCapability(
                    new ServiceIdentity("civiceconomy-admin"),
                    "grant-public-works-reserve",
                    publicWorks,
                    FiscalCapability.RESERVE_FUNDS,
                    treasury,
                    "Permit bridge-project Reservations"));
        }

        try (CivicDatabase reopened = CivicDatabase.open(databaseFile, identity())) {
            FiscalLedger ledger = FiscalLedger.authorized(
                    reopened, ignored -> MoneyAmount.ofMinorUnits(1_000));

            ledger.reserve(new ReserveFunds(
                    publicWorks,
                    "authorized-reservation",
                    treasury,
                    MoneyAmount.ofMinorUnits(300),
                    "Authorized bridge contract"));

            assertEquals(MoneyAmount.ofMinorUnits(300), ledger.reservedBalance(treasury));
        }
    }

    @Test
    void grantDoesNotAuthorizeAnotherAccountOrCapability() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        AccountId anotherTreasury = new AccountId("nation:borealis:treasury");
        ServiceIdentity publicWorks = new ServiceIdentity("public-works");

        try (CivicDatabase database = database()) {
            FiscalAuthorization authorization = new FiscalAuthorization(database);
            authorization.register(new RegisterFiscalService(
                    publicWorks, "publicworksmod", "Public Works integration"));
            authorization.grant(new GrantFiscalCapability(
                    new ServiceIdentity("civiceconomy-admin"),
                    "grant-one-account",
                    publicWorks,
                    FiscalCapability.RESERVE_FUNDS,
                    treasury,
                    "Permit one National Treasury"));
            FiscalLedger ledger = FiscalLedger.authorized(
                    database, ignored -> MoneyAmount.ofMinorUnits(1_000));

            ledger.reserve(new ReserveFunds(
                    publicWorks,
                    "allowed-reservation",
                    treasury,
                    MoneyAmount.ofMinorUnits(100),
                    "Allowed scoped hold"));

            assertThrows(
                    FiscalAccessDeniedException.class,
                    () -> ledger.reserve(new ReserveFunds(
                            publicWorks,
                            "other-account-reservation",
                            anotherTreasury,
                            MoneyAmount.ofMinorUnits(100),
                            "Wrong account")));
            assertThrows(
                    FiscalAccessDeniedException.class,
                    () -> ledger.openEscrow(new OpenEscrow(
                            publicWorks,
                            "wrong-capability-escrow",
                            treasury,
                            MoneyAmount.ofMinorUnits(100),
                            "bridge-project",
                            "Wrong capability",
                            Instant.now().plusSeconds(3_600))));
            assertEquals(MoneyAmount.ofMinorUnits(100), ledger.reservedBalance(treasury));
            assertEquals(MoneyAmount.ZERO, ledger.reservedBalance(anotherTreasury));
        }
    }

    @Test
    void unauthorizedSettlementCreatesNoTransactionAndCallsNoExternalPayment() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        AccountId recipient = new AccountId("organization:bridge-builder:fiscal");
        ServiceIdentity publicWorks = new ServiceIdentity("public-works");

        try (CivicDatabase database = database()) {
            FiscalAuthorization authorization = new FiscalAuthorization(database);
            authorization.register(new RegisterFiscalService(
                    publicWorks, "publicworksmod", "Public Works integration"));
            authorization.grant(new GrantFiscalCapability(
                    new ServiceIdentity("civiceconomy-admin"),
                    "grant-reserve-only",
                    publicWorks,
                    FiscalCapability.RESERVE_FUNDS,
                    treasury,
                    "Allow holds but not payments"));
            FiscalLedger ledger = FiscalLedger.authorized(
                    database, ignored -> MoneyAmount.ofMinorUnits(1_000));
            Reservation reservation = ledger.reserve(new ReserveFunds(
                    publicWorks,
                    "payment-hold",
                    treasury,
                    MoneyAmount.ofMinorUnits(300),
                    "Bridge contract hold"));
            AtomicInteger externalCalls = new AtomicInteger();
            PaymentCoordinator coordinator = PaymentCoordinator.authorized(
                    database, ignored -> externalCalls.incrementAndGet());

            assertThrows(
                    FiscalAccessDeniedException.class,
                    () -> coordinator.settle(
                            new SettleReservation(
                                    publicWorks,
                                    "unauthorized-payment",
                                    reservation.reservationId(),
                                    recipient,
                                    MoneyAmount.ofMinorUnits(300)),
                            FailurePoint.NONE));

            assertEquals(0, externalCalls.get());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> coordinator.transaction("unauthorized-payment"));
            assertEquals(MoneyAmount.ofMinorUnits(300), ledger.reservedBalance(treasury));
        }
    }

    @Test
    void registrationAndGrantReplayAreStableAndChangedPayloadsConflict() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        AccountId anotherTreasury = new AccountId("nation:borealis:treasury");
        ServiceIdentity publicWorks = new ServiceIdentity("public-works");
        ServiceIdentity administrator = new ServiceIdentity("civiceconomy-admin");

        try (CivicDatabase database = database()) {
            FiscalAuthorization authorization = new FiscalAuthorization(database);
            RegisterFiscalService registration = new RegisterFiscalService(
                    publicWorks, "publicworksmod", "Public Works integration");
            assertEquals(authorization.register(registration), authorization.register(registration));
            assertThrows(
                    FiscalServiceRegistrationConflictException.class,
                    () -> authorization.register(new RegisterFiscalService(
                            publicWorks, "publicworksmod", "Impersonated display name")));

            GrantFiscalCapability grant = new GrantFiscalCapability(
                    administrator,
                    "stable-grant",
                    publicWorks,
                    FiscalCapability.RESERVE_FUNDS,
                    treasury,
                    "Permit one National Treasury");
            assertEquals(authorization.grant(grant), authorization.grant(grant));
            assertThrows(
                    FiscalGrantConflictException.class,
                    () -> authorization.grant(new GrantFiscalCapability(
                            administrator,
                            "stable-grant",
                            publicWorks,
                            FiscalCapability.RESERVE_FUNDS,
                            anotherTreasury,
                            "Changed account scope")));
            assertThrows(
                    UnknownFiscalServiceException.class,
                    () -> authorization.grant(new GrantFiscalCapability(
                            administrator,
                            "unknown-service-grant",
                            new ServiceIdentity("missing-service"),
                            FiscalCapability.RESERVE_FUNDS,
                            treasury,
                            "Must fail closed")));
        }
    }

    @Test
    void detailedAccountReadsRequireAnExactReadGrant() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        ServiceIdentity auditor = new ServiceIdentity("public-auditor");

        try (CivicDatabase database = database()) {
            FiscalAuthorization authorization = new FiscalAuthorization(database);
            authorization.register(new RegisterFiscalService(
                    auditor, "auditmod", "Public fiscal auditor"));
            FiscalLedger ledger = FiscalLedger.authorized(
                    database, ignored -> MoneyAmount.ofMinorUnits(1_000));

            assertThrows(
                    FiscalAccessDeniedException.class,
                    () -> ledger.availableBalance(auditor, treasury));

            authorization.grant(new GrantFiscalCapability(
                    new ServiceIdentity("civiceconomy-admin"),
                    "grant-auditor-read",
                    auditor,
                    FiscalCapability.READ_ACCOUNT,
                    treasury,
                    "Permit detailed Treasury audit"));

            assertEquals(
                    MoneyAmount.ofMinorUnits(1_000),
                    ledger.availableBalance(auditor, treasury));
            assertEquals(MoneyAmount.ZERO, ledger.reservedBalance(auditor, treasury));
            assertEquals(java.util.List.of(), ledger.ledgerEntries(auditor, treasury));
        }
    }

    @Test
    void paymentAndRecoveryDetailsRequireReadAuthorityOnTheSourceAccount() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        AccountId recipient = new AccountId("organization:bridge-builder:fiscal");
        ServiceIdentity publicWorks = new ServiceIdentity("public-works");
        ServiceIdentity administrator = new ServiceIdentity("civiceconomy-admin");

        try (CivicDatabase database = database()) {
            FiscalAuthorization authorization = new FiscalAuthorization(database);
            authorization.register(new RegisterFiscalService(
                    publicWorks, "publicworksmod", "Public Works integration"));
            for (FiscalCapability capability :
                    new FiscalCapability[] {
                        FiscalCapability.RESERVE_FUNDS, FiscalCapability.SETTLE_PAYMENT
                    }) {
                authorization.grant(new GrantFiscalCapability(
                        administrator,
                        "grant-payment-" + capability,
                        publicWorks,
                        capability,
                        treasury,
                        "Permit payment creation"));
            }
            FiscalLedger ledger = FiscalLedger.authorized(
                    database, ignored -> MoneyAmount.ofMinorUnits(1_000));
            Reservation reservation = ledger.reserve(new ReserveFunds(
                    publicWorks,
                    "read-protected-hold",
                    treasury,
                    MoneyAmount.ofMinorUnits(300),
                    "Read-protected payment"));
            PaymentCoordinator coordinator = PaymentCoordinator.authorized(database, ignored -> {});
            PaymentTransaction paid = coordinator.settle(
                    new SettleReservation(
                            publicWorks,
                            "read-protected-payment",
                            reservation.reservationId(),
                            recipient,
                            MoneyAmount.ofMinorUnits(300)),
                    FailurePoint.NONE);

            assertThrows(
                    FiscalAccessDeniedException.class,
                    () -> coordinator.transaction(publicWorks, paid.requestId()));
            assertThrows(
                    FiscalAccessDeniedException.class,
                    () -> coordinator.recoveryAudit(publicWorks, paid.transactionId()));

            authorization.grant(new GrantFiscalCapability(
                    administrator,
                    "grant-payment-read",
                    publicWorks,
                    FiscalCapability.READ_ACCOUNT,
                    treasury,
                    "Permit payment audit"));

            assertEquals(paid, coordinator.transaction(publicWorks, paid.requestId()));
            assertEquals(
                    java.util.List.of(),
                    coordinator.recoveryAudit(publicWorks, paid.transactionId()));
        }
    }

    @Test
    void escrowDetailsRequireReadAuthorityEvenWhenTheServiceCanManageEscrow() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        ServiceIdentity publicWorks = new ServiceIdentity("public-works");
        ServiceIdentity administrator = new ServiceIdentity("civiceconomy-admin");

        try (CivicDatabase database = database()) {
            FiscalAuthorization authorization = new FiscalAuthorization(database);
            authorization.register(new RegisterFiscalService(
                    publicWorks, "publicworksmod", "Public Works integration"));
            authorization.grant(new GrantFiscalCapability(
                    administrator,
                    "grant-escrow-management",
                    publicWorks,
                    FiscalCapability.MANAGE_ESCROW,
                    treasury,
                    "Permit Escrow management"));
            FiscalLedger ledger = FiscalLedger.authorized(
                    database, ignored -> MoneyAmount.ofMinorUnits(1_000));
            Escrow escrow = ledger.openEscrow(new OpenEscrow(
                    publicWorks,
                    "read-protected-escrow",
                    treasury,
                    MoneyAmount.ofMinorUnits(300),
                    "bridge-project",
                    "Read-protected Escrow",
                    Instant.now().plusSeconds(3_600)));

            assertThrows(
                    FiscalAccessDeniedException.class,
                    () -> ledger.escrow(publicWorks, escrow.escrowId()));

            authorization.grant(new GrantFiscalCapability(
                    administrator,
                    "grant-escrow-read",
                    publicWorks,
                    FiscalCapability.READ_ACCOUNT,
                    treasury,
                    "Permit Escrow inspection"));

            assertEquals(escrow, ledger.escrow(publicWorks, escrow.escrowId()));
        }
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("authorization.sqlite3"),
                identity());
    }

    private static DatabaseIdentity identity() {
        return new DatabaseIdentity(
                UUID.fromString("5fb53498-5925-4a7f-ab10-bbd7e65f7a91"),
                "0.1.0",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");
    }
}
