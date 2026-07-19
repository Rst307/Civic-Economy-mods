package org.civiceconomy.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    @Test
    void revocationSurvivesReopenAndBlocksNewWritesWithoutDeletingExistingState() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        ServiceIdentity publicWorks = new ServiceIdentity("public-works");
        ServiceIdentity administrator = new ServiceIdentity("civiceconomy-admin");
        Path databaseFile = temporaryDirectory.resolve("revoked-authorization.sqlite3");
        FiscalCapabilityRevocation revocation;

        try (CivicDatabase database = CivicDatabase.open(databaseFile, identity())) {
            FiscalAuthorization authorization = new FiscalAuthorization(database);
            authorization.register(new RegisterFiscalService(
                    publicWorks, "publicworksmod", "Public Works integration"));
            FiscalCapabilityGrant reserveGrant = authorization.grant(new GrantFiscalCapability(
                    administrator,
                    "grant-revocable-reserve",
                    publicWorks,
                    FiscalCapability.RESERVE_FUNDS,
                    treasury,
                    "Permit bridge-project Reservations"));
            authorization.grant(new GrantFiscalCapability(
                    administrator,
                    "grant-revocation-read",
                    publicWorks,
                    FiscalCapability.READ_ACCOUNT,
                    treasury,
                    "Permit post-revocation inspection"));
            FiscalLedger ledger = FiscalLedger.authorized(
                    database, ignored -> MoneyAmount.ofMinorUnits(1_000));
            ledger.reserve(new ReserveFunds(
                    publicWorks,
                    "existing-before-revocation",
                    treasury,
                    MoneyAmount.ofMinorUnits(100),
                    "Existing commitment"));
            RevokeFiscalCapability request = new RevokeFiscalCapability(
                    administrator,
                    "revoke-reserve-grant",
                    reserveGrant.grantId(),
                    "Integration contract ended");

            revocation = authorization.revoke(request);
            assertEquals(revocation, authorization.revoke(request));
        }

        try (CivicDatabase reopened = CivicDatabase.open(databaseFile, identity())) {
            FiscalLedger ledger = FiscalLedger.authorized(
                    reopened, ignored -> MoneyAmount.ofMinorUnits(1_000));

            assertThrows(
                    FiscalAccessDeniedException.class,
                    () -> ledger.reserve(new ReserveFunds(
                            publicWorks,
                            "blocked-after-revocation",
                            treasury,
                            MoneyAmount.ofMinorUnits(100),
                            "Must be denied")));
            assertEquals(
                    MoneyAmount.ofMinorUnits(100),
                    ledger.reservedBalance(publicWorks, treasury));
            assertEquals(revocation.grantId(), revocation.grant().grantId());
        }
    }

    @Test
    void revokedScopeCanBeGrantedAgainWithNewHistoryAndRevocationConflictsFailClosed() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        AccountId anotherTreasury = new AccountId("nation:borealis:treasury");
        ServiceIdentity publicWorks = new ServiceIdentity("public-works");
        ServiceIdentity administrator = new ServiceIdentity("civiceconomy-admin");

        try (CivicDatabase database = database()) {
            FiscalAuthorization authorization = new FiscalAuthorization(database);
            authorization.register(new RegisterFiscalService(
                    publicWorks, "publicworksmod", "Public Works integration"));
            FiscalCapabilityGrant original = authorization.grant(new GrantFiscalCapability(
                    administrator,
                    "grant-original-reserve",
                    publicWorks,
                    FiscalCapability.RESERVE_FUNDS,
                    treasury,
                    "Original authority"));
            RevokeFiscalCapability revocation = new RevokeFiscalCapability(
                    administrator,
                    "revoke-original-reserve",
                    original.grantId(),
                    "Original contract ended");
            FiscalCapabilityRevocation revoked = authorization.revoke(revocation);

            assertEquals(revoked, authorization.revoke(revocation));
            assertThrows(
                    FiscalRevocationConflictException.class,
                    () -> authorization.revoke(new RevokeFiscalCapability(
                            administrator,
                            "revoke-original-reserve",
                            original.grantId(),
                            "Changed reason")));
            assertThrows(
                    UnknownFiscalGrantException.class,
                    () -> authorization.revoke(new RevokeFiscalCapability(
                            administrator,
                            "revoke-missing-grant",
                            UUID.randomUUID(),
                            "Missing grant")));

            FiscalCapabilityGrant replacement = authorization.grant(new GrantFiscalCapability(
                    administrator,
                    "grant-replacement-reserve",
                    publicWorks,
                    FiscalCapability.RESERVE_FUNDS,
                    treasury,
                    "Replacement authority"));

            assertNotEquals(original.grantId(), replacement.grantId());
            assertEquals(original.grantId(), revoked.grantId());
            FiscalLedger ledger = FiscalLedger.authorized(
                    database, ignored -> MoneyAmount.ofMinorUnits(1_000));
            ledger.reserve(new ReserveFunds(
                    publicWorks,
                    "replacement-authorized-hold",
                    treasury,
                    MoneyAmount.ofMinorUnits(100),
                    "Replacement authority works"));
            assertThrows(
                    FiscalAccessDeniedException.class,
                    () -> ledger.reserve(new ReserveFunds(
                            publicWorks,
                            "replacement-wrong-account",
                            anotherTreasury,
                            MoneyAmount.ofMinorUnits(100),
                            "Scope remains exact")));
        }
    }

    @Test
    void disablingAServiceOverridesAllGrantsAndSurvivesReopen() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        ServiceIdentity publicWorks = new ServiceIdentity("public-works");
        ServiceIdentity administrator = new ServiceIdentity("civiceconomy-admin");
        Path databaseFile = temporaryDirectory.resolve("disabled-service.sqlite3");
        FiscalServiceStateChange disabled;

        try (CivicDatabase database = CivicDatabase.open(databaseFile, identity())) {
            FiscalAuthorization authorization = new FiscalAuthorization(database);
            authorization.register(new RegisterFiscalService(
                    publicWorks, "publicworksmod", "Public Works integration"));
            for (FiscalCapability capability :
                    new FiscalCapability[] {
                        FiscalCapability.READ_ACCOUNT, FiscalCapability.RESERVE_FUNDS
                    }) {
                authorization.grant(new GrantFiscalCapability(
                        administrator,
                        "grant-before-disable-" + capability,
                        publicWorks,
                        capability,
                        treasury,
                        "Authority before disable"));
            }
            ChangeFiscalServiceState request = new ChangeFiscalServiceState(
                    administrator,
                    "disable-public-works",
                    publicWorks,
                    FiscalServiceState.DISABLED,
                    "Integration removed from server");

            disabled = authorization.changeState(request);
            assertEquals(disabled, authorization.changeState(request));
        }

        try (CivicDatabase reopened = CivicDatabase.open(databaseFile, identity())) {
            FiscalLedger ledger = FiscalLedger.authorized(
                    reopened, ignored -> MoneyAmount.ofMinorUnits(1_000));

            assertThrows(
                    FiscalAccessDeniedException.class,
                    () -> ledger.availableBalance(publicWorks, treasury));
            assertThrows(
                    FiscalAccessDeniedException.class,
                    () -> ledger.reserve(new ReserveFunds(
                            publicWorks,
                            "blocked-disabled-reservation",
                            treasury,
                            MoneyAmount.ofMinorUnits(100),
                            "Disabled service cannot write")));
            assertEquals(FiscalServiceState.DISABLED, disabled.state());
            assertEquals(publicWorks, disabled.serviceIdentity());
        }
    }

    @Test
    void serviceCanBeReenabledAndStateRequestConflictsFailClosed() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        ServiceIdentity publicWorks = new ServiceIdentity("public-works");
        ServiceIdentity administrator = new ServiceIdentity("civiceconomy-admin");

        try (CivicDatabase database = database()) {
            FiscalAuthorization authorization = new FiscalAuthorization(database);
            authorization.register(new RegisterFiscalService(
                    publicWorks, "publicworksmod", "Public Works integration"));
            authorization.grant(new GrantFiscalCapability(
                    administrator,
                    "grant-reenable-reserve",
                    publicWorks,
                    FiscalCapability.RESERVE_FUNDS,
                    treasury,
                    "Persistent grant"));
            ChangeFiscalServiceState disable = new ChangeFiscalServiceState(
                    administrator,
                    "disable-for-maintenance",
                    publicWorks,
                    FiscalServiceState.DISABLED,
                    "Maintenance window");

            assertEquals(authorization.changeState(disable), authorization.changeState(disable));
            assertThrows(
                    FiscalServiceStateConflictException.class,
                    () -> authorization.changeState(new ChangeFiscalServiceState(
                            administrator,
                            "disable-for-maintenance",
                            publicWorks,
                            FiscalServiceState.ENABLED,
                            "Changed payload")));
            assertThrows(
                    UnknownFiscalServiceException.class,
                    () -> authorization.changeState(new ChangeFiscalServiceState(
                            administrator,
                            "disable-missing-service",
                            new ServiceIdentity("missing-service"),
                            FiscalServiceState.DISABLED,
                            "Unknown service")));

            authorization.changeState(new ChangeFiscalServiceState(
                    administrator,
                    "reenable-after-maintenance",
                    publicWorks,
                    FiscalServiceState.ENABLED,
                    "Maintenance complete"));

            FiscalLedger ledger = FiscalLedger.authorized(
                    database, ignored -> MoneyAmount.ofMinorUnits(1_000));
            ledger.reserve(new ReserveFunds(
                    publicWorks,
                    "authorized-after-reenable",
                    treasury,
                    MoneyAmount.ofMinorUnits(100),
                    "Grant is active again"));
            assertEquals(MoneyAmount.ofMinorUnits(100), ledger.reservedBalance(treasury));
        }
    }

    @Test
    void disablingServiceDoesNotStrandAlreadyAppliedPaymentRecovery() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        AccountId recipient = new AccountId("organization:bridge-builder:fiscal");
        ServiceIdentity publicWorks = new ServiceIdentity("public-works");
        ServiceIdentity administrator = new ServiceIdentity("civiceconomy-admin");
        AtomicInteger externalCalls = new AtomicInteger();

        try (CivicDatabase database = database()) {
            FiscalAuthorization authorization = new FiscalAuthorization(database);
            authorization.register(new RegisterFiscalService(
                    publicWorks, "publicworksmod", "Public Works integration"));
            for (FiscalCapability capability :
                    new FiscalCapability[] {
                        FiscalCapability.RESERVE_FUNDS,
                        FiscalCapability.SETTLE_PAYMENT,
                        FiscalCapability.READ_ACCOUNT
                    }) {
                authorization.grant(new GrantFiscalCapability(
                        administrator,
                        "grant-recovery-" + capability,
                        publicWorks,
                        capability,
                        treasury,
                        "Permit recoverable payment"));
            }
            FiscalLedger ledger = FiscalLedger.authorized(
                    database, ignored -> MoneyAmount.ofMinorUnits(1_000));
            Reservation reservation = ledger.reserve(new ReserveFunds(
                    publicWorks,
                    "recoverable-payment-hold",
                    treasury,
                    MoneyAmount.ofMinorUnits(300),
                    "Recoverable payment"));
            PaymentCoordinator coordinator = PaymentCoordinator.authorized(
                    database, ignored -> externalCalls.incrementAndGet());
            String requestId = "recoverable-payment";

            assertThrows(
                    SimulatedCrash.class,
                    () -> coordinator.settle(
                            new SettleReservation(
                                    publicWorks,
                                    requestId,
                                    reservation.reservationId(),
                                    recipient,
                                    MoneyAmount.ofMinorUnits(300)),
                            FailurePoint.AFTER_EXTERNAL_APPLIED));
            authorization.changeState(new ChangeFiscalServiceState(
                    administrator,
                    "disable-before-recovery",
                    publicWorks,
                    FiscalServiceState.DISABLED,
                    "Caller disabled after external application"));

            assertThrows(
                    FiscalAccessDeniedException.class,
                    () -> coordinator.transaction(publicWorks, requestId));
            coordinator.recoverIncomplete();

            assertEquals(1, externalCalls.get());
            assertEquals("CIVIC_COMMITTED", database.paymentTransaction(requestId).state());
            assertEquals(MoneyAmount.ZERO, ledger.reservedBalance(treasury));
        }
    }

    @Test
    void ownerBoundSessionCannotSubmitAnotherServiceIdentity() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        ServiceIdentity publicWorks = new ServiceIdentity("public-works");
        ServiceIdentity impostor = new ServiceIdentity("impostor-service");
        ServiceIdentity administrator = new ServiceIdentity("civiceconomy-admin");

        try (CivicDatabase database = database()) {
            FiscalAuthorization authorization = new FiscalAuthorization(database);
            authorization.register(new RegisterFiscalService(
                    publicWorks, "publicworksmod", "Public Works integration"));
            authorization.register(new RegisterFiscalService(
                    impostor, "impostormod", "Impostor integration"));
            for (ServiceIdentity service : new ServiceIdentity[] {publicWorks, impostor}) {
                authorization.grant(new GrantFiscalCapability(
                        administrator,
                        "grant-session-reserve-" + service.value(),
                        service,
                        FiscalCapability.RESERVE_FUNDS,
                        treasury,
                        "Both identities have database authority"));
            }
            FiscalServiceSession publicWorksSession =
                    authorization.openSession(publicWorks, "publicworksmod");
            FiscalLedger ledger = FiscalLedger.authorized(
                    database,
                    ignored -> MoneyAmount.ofMinorUnits(1_000),
                    publicWorksSession);

            ledger.reserve(new ReserveFunds(
                    publicWorks,
                    "session-authorized-hold",
                    treasury,
                    MoneyAmount.ofMinorUnits(100),
                    "Correct session identity"));
            assertThrows(
                    FiscalServiceIdentityMismatchException.class,
                    () -> ledger.reserve(new ReserveFunds(
                            impostor,
                            "session-impersonation-hold",
                            treasury,
                            MoneyAmount.ofMinorUnits(100),
                            "Must fail before authorization")));
            assertEquals(MoneyAmount.ofMinorUnits(100), ledger.reservedBalance(treasury));
        }
    }

    @Test
    void sessionRejectsOwnerThatDoesNotMatchDurableRegistration() {
        ServiceIdentity publicWorks = new ServiceIdentity("public-works");

        try (CivicDatabase database = database()) {
            FiscalAuthorization authorization = new FiscalAuthorization(database);
            authorization.register(new RegisterFiscalService(
                    publicWorks, "publicworksmod", "Public Works integration"));

            assertThrows(
                    FiscalServiceOwnerMismatchException.class,
                    () -> authorization.openSession(publicWorks, "impostormod"));
        }
    }

    @Test
    void ownerBoundPaymentSessionRejectsAnotherServiceBeforeExternalPayment() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        AccountId recipient = new AccountId("player:river");
        ServiceIdentity publicWorks = new ServiceIdentity("public-works");
        ServiceIdentity impostor = new ServiceIdentity("impostor-service");
        ServiceIdentity administrator = new ServiceIdentity("civiceconomy-admin");
        AtomicInteger externalCalls = new AtomicInteger();

        try (CivicDatabase database = database()) {
            FiscalAuthorization authorization = new FiscalAuthorization(database);
            authorization.register(new RegisterFiscalService(
                    publicWorks, "publicworksmod", "Public Works integration"));
            authorization.register(new RegisterFiscalService(
                    impostor, "impostormod", "Impostor integration"));
            for (ServiceIdentity service : new ServiceIdentity[] {publicWorks, impostor}) {
                for (FiscalCapability capability : new FiscalCapability[] {
                    FiscalCapability.RESERVE_FUNDS, FiscalCapability.SETTLE_PAYMENT
                }) {
                    authorization.grant(new GrantFiscalCapability(
                            administrator,
                            "grant-payment-session-" + service.value() + "-" + capability,
                            service,
                            capability,
                            treasury,
                            "Both identities have database authority"));
                }
            }
            FiscalServiceSession publicWorksSession =
                    authorization.openSession(publicWorks, "publicworksmod");
            FiscalLedger ledger = FiscalLedger.authorized(
                    database,
                    ignored -> MoneyAmount.ofMinorUnits(1_000),
                    publicWorksSession);
            Reservation reservation = ledger.reserve(new ReserveFunds(
                    publicWorks,
                    "payment-session-hold",
                    treasury,
                    MoneyAmount.ofMinorUnits(100),
                    "Correct session identity"));
            PaymentCoordinator coordinator = PaymentCoordinator.authorized(
                    database,
                    ignored -> externalCalls.incrementAndGet(),
                    publicWorksSession);

            assertThrows(
                    FiscalServiceIdentityMismatchException.class,
                    () -> coordinator.settle(
                            new SettleReservation(
                                    impostor,
                                    "payment-session-impersonation",
                                    reservation.reservationId(),
                                    recipient,
                                    MoneyAmount.ofMinorUnits(100)),
                            FailurePoint.NONE));
            assertEquals(0, externalCalls.get());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> coordinator.transaction("payment-session-impersonation"));
        }
    }

    @Test
    void authorizationViewShowsOwnerStateAndRevokedExactAccountGrant() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        ServiceIdentity publicWorks = new ServiceIdentity("public-works");
        ServiceIdentity administrator = new ServiceIdentity("civiceconomy-admin");

        try (CivicDatabase database = database()) {
            FiscalAuthorization authorization = new FiscalAuthorization(database);
            authorization.register(new RegisterFiscalService(
                    publicWorks,
                    "publicworksmod",
                    "Public Works integration",
                    administrator,
                    "register-view-service",
                    "Trusted OP registration"));
            FiscalCapabilityGrant grant = authorization.grant(new GrantFiscalCapability(
                    administrator,
                    "grant-view-reserve",
                    publicWorks,
                    FiscalCapability.RESERVE_FUNDS,
                    treasury,
                    "View fixture"));
            authorization.revoke(new RevokeFiscalCapability(
                    administrator,
                    "revoke-view-reserve",
                    grant.grantId(),
                    "View revoked fixture"));
            authorization.changeState(new ChangeFiscalServiceState(
                    administrator,
                    "disable-view-service",
                    publicWorks,
                    FiscalServiceState.DISABLED,
                    "View disabled fixture"));

            FiscalServiceAuthorizationView view = authorization.describe(publicWorks);

            assertEquals("publicworksmod", view.service().ownerModId());
            assertEquals(administrator, view.service().administrator());
            assertEquals("register-view-service", view.service().requestId());
            assertEquals("Trusted OP registration", view.service().reason());
            assertEquals(FiscalServiceState.DISABLED, view.state());
            assertEquals(1, view.grants().size());
            assertEquals(grant.grantId(), view.grants().getFirst().grant().grantId());
            assertEquals(false, view.grants().getFirst().active());
            assertEquals(
                    "View revoked fixture",
                    view.grants().getFirst().revocation().orElseThrow().reason());
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
