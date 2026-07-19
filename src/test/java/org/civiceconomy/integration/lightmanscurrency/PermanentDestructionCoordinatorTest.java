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
import org.civiceconomy.fiscal.FiscalAuthorization;
import org.civiceconomy.fiscal.FiscalAccessDeniedException;
import org.civiceconomy.fiscal.FiscalCapability;
import org.civiceconomy.fiscal.FiscalServiceSession;
import org.civiceconomy.fiscal.FiscalServiceIdentityMismatchException;
import org.civiceconomy.fiscal.FiscalTestSessions;
import org.civiceconomy.fiscal.GrantFiscalCapability;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.RegisterFiscalService;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.civiceconomy.monetary.ConfirmMonetarySupplyChange;
import org.civiceconomy.monetary.ConfirmPermanentDestruction;
import org.civiceconomy.monetary.DestructionExceedsNetIssuanceException;
import org.civiceconomy.monetary.MonetarySupplyChange;
import org.civiceconomy.monetary.MonetarySupplyEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PermanentDestructionCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ServiceIdentity SERVICE = new ServiceIdentity("maintenance-service");
    private static final ServiceIdentity OTHER_SERVICE = new ServiceIdentity("other-service");
    private static final AccountId TREASURY = new AccountId("nation:aurora:treasury");

    @TempDir Path temporaryDirectory;

    @Test
    void restartAfterExternalDestructionRecordsItWithoutDestroyingTwice() {
        AtomicLong lcBalance = new AtomicLong(1_000L);
        Set<UUID> applied = new HashSet<>();
        AtomicBoolean crashAfterFirstEffect = new AtomicBoolean(true);
        ConfirmPermanentDestruction request = new ConfirmPermanentDestruction(
                SERVICE,
                "maintenance-destruction-1",
                TREASURY,
                MoneyAmount.ofMinorUnits(600L),
                "Territory maintenance cycle 1");

        try (CivicDatabase database = database()) {
            authorize(database);
            seedIssuance(database, "seed-issued-supply", "mint-batch:seed");
            PermanentDestructionCoordinator coordinator = coordinator(
                    database,
                    destruction -> {
                        if (applied.add(destruction.destructionId())) {
                            lcBalance.addAndGet(-destruction.amount().minorUnits());
                            if (crashAfterFirstEffect.getAndSet(false)) {
                                throw new IllegalStateException("simulated process death");
                            }
                        }
                    });

            assertThrows(IllegalStateException.class, () -> coordinator.confirm(request));
            assertEquals(400L, lcBalance.get());
            assertEquals(1_000L, database.cumulativeNetIssuanceMinorUnits());
            assertThrows(
                    DestructionExceedsNetIssuanceException.class,
                    () -> coordinator.confirm(new ConfirmPermanentDestruction(
                            SERVICE,
                            "maintenance-destruction-2",
                            TREASURY,
                            MoneyAmount.ofMinorUnits(500L),
                            "Must not consume recovery capacity")));
            assertEquals(400L, lcBalance.get());
        }

        try (CivicDatabase database = database()) {
            PermanentDestructionCoordinator recovery = coordinator(
                    database,
                    destruction -> {
                        if (applied.add(destruction.destructionId())) {
                            lcBalance.addAndGet(-destruction.amount().minorUnits());
                        }
                    });

            recovery.recoverAll();
            MonetarySupplyEvent event = recovery.confirm(request);

            assertEquals(400L, lcBalance.get());
            assertEquals(MonetarySupplyChange.PERMANENT_DESTRUCTION, event.change());
            assertEquals(MoneyAmount.ofMinorUnits(600L), event.amount());
            assertEquals(400L, database.cumulativeNetIssuanceMinorUnits());
            assertEquals(2, database.monetarySupplyEvents().size());

            applied.clear();
            lcBalance.set(1_000L);
            recovery.recoverAll();
            assertEquals(1_000L, lcBalance.get());
            assertEquals(400L, database.cumulativeNetIssuanceMinorUnits());
            assertEquals(2, database.monetarySupplyEvents().size());
        }
    }

    @Test
    void externalApplicationStatePersistsBeforeMonetarySupplyCommit() {
        UUID operationId = UUID.randomUUID();
        try (CivicDatabase database = database()) {
            seedIssuance(database, "seed-external-applied-state", "mint-batch:external-state");
            database.preparePermanentDestruction(
                    operationId,
                    SERVICE.value(),
                    "destruction-external-applied-state",
                    TREASURY.value(),
                    600L,
                    "player:11111111-1111-1111-1111-111111111111",
                    "Persist external Permanent Destruction result",
                    NOW.toEpochMilli());

            var marked = database.markPermanentDestructionExternalApplied(
                    operationId, NOW.plusSeconds(1L).toEpochMilli());

            assertEquals("PREPARED", marked.state());
            assertEquals(1_000L, database.cumulativeNetIssuanceMinorUnits());
            assertEquals(
                    operationId,
                    database.pendingPermanentDestructionOperations(SERVICE.value())
                            .getFirst()
                            .operationId());
        }

        try (CivicDatabase reopened = database()) {
            var persisted = reopened.permanentDestructionOperation(
                    SERVICE.value(), "destruction-external-applied-state");
            assertEquals("PREPARED", persisted.state());
            assertEquals(
                    NOW.plusSeconds(1L).toEpochMilli(),
                    persisted.externalAppliedAtEpochMillis());
            assertEquals(1_000L, reopened.cumulativeNetIssuanceMinorUnits());
        }
    }

    @Test
    void crashAfterExternalApplicationRecordRecoversWithoutSecondDestruction() {
        AtomicLong lcBalance = new AtomicLong(1_000L);
        Set<UUID> applied = new HashSet<>();
        ConfirmPermanentDestruction request = new ConfirmPermanentDestruction(
                SERVICE,
                "destruction-recorded-before-commit",
                TREASURY,
                MoneyAmount.ofMinorUnits(600L),
                "Crash after external application record");

        try (CivicDatabase database = database()) {
            authorize(database);
            seedIssuance(database, "seed-recorded-before-commit", "mint-batch:recorded");
            PermanentDestructionCoordinator coordinator = coordinator(
                    database,
                    destruction -> {
                        if (applied.add(destruction.destructionId())) {
                            lcBalance.addAndGet(-destruction.amount().minorUnits());
                        }
                    },
                    new PermanentDestructionProgressObserver() {
                        @Override
                        public void afterExternalApplied(
                                org.civiceconomy.monetary.ExternalPermanentDestruction ignored) {}

                        @Override
                        public void afterExternalRecorded(
                                org.civiceconomy.monetary.ExternalPermanentDestruction ignored) {
                            throw new IllegalStateException("simulated recorded-result crash");
                        }
                    });

            assertThrows(IllegalStateException.class, () -> coordinator.confirm(request));
            var pending = database.permanentDestructionOperation(
                    SERVICE.value(), request.requestId());
            assertEquals("PREPARED", pending.state());
            assertEquals(NOW.toEpochMilli(), pending.externalAppliedAtEpochMillis());
            assertEquals(400L, lcBalance.get());
            assertEquals(1_000L, database.cumulativeNetIssuanceMinorUnits());
        }

        try (CivicDatabase reopened = database()) {
            coordinator(
                            reopened,
                            destruction -> {
                                if (applied.add(destruction.destructionId())) {
                                    lcBalance.addAndGet(-destruction.amount().minorUnits());
                                }
                            })
                    .recoverAll();

            assertEquals(400L, lcBalance.get());
            assertEquals(400L, reopened.cumulativeNetIssuanceMinorUnits());
            assertEquals(
                    "COMMITTED",
                    reopened.permanentDestructionOperation(
                                    SERVICE.value(), request.requestId())
                            .state());
        }
    }

    @Test
    void identityAuthorityAndReplayPayloadFailBeforeExternalDestruction() {
        AtomicLong externalCalls = new AtomicLong();
        try (CivicDatabase database = database()) {
            authorize(database);
            seedIssuance(database, "seed-authority-supply", "mint-batch:authority");
            PermanentDestructionCoordinator coordinator = coordinator(
                    database, ignored -> externalCalls.incrementAndGet());

            assertThrows(
                    FiscalServiceIdentityMismatchException.class,
                    () -> coordinator.confirm(new ConfirmPermanentDestruction(
                            new ServiceIdentity("impostor-service"),
                            "impostor-destruction",
                            TREASURY,
                            MoneyAmount.ofMinorUnits(100L),
                            "Must fail at session identity")));
            assertThrows(
                    FiscalAccessDeniedException.class,
                    () -> coordinator.confirm(new ConfirmPermanentDestruction(
                            SERVICE,
                            "wrong-account-destruction",
                            new AccountId("nation:borealis:treasury"),
                            MoneyAmount.ofMinorUnits(100L),
                            "Must fail at exact account grant")));
            ConfirmPermanentDestruction request = new ConfirmPermanentDestruction(
                    SERVICE,
                    "authorized-destruction",
                    TREASURY,
                    MoneyAmount.ofMinorUnits(100L),
                    "player:11111111-1111-1111-1111-111111111111",
                    "Authorized exact account destruction");
            coordinator.confirm(request);
            assertThrows(
                    org.civiceconomy.fiscal.IdempotencyConflictException.class,
                    () -> coordinator.confirm(new ConfirmPermanentDestruction(
                            SERVICE,
                            request.requestId(),
                            TREASURY,
                            MoneyAmount.ofMinorUnits(101L),
                            request.operatorIdentity(),
                            request.reason())));
            assertThrows(
                    org.civiceconomy.fiscal.IdempotencyConflictException.class,
                    () -> coordinator.confirm(new ConfirmPermanentDestruction(
                            SERVICE,
                            request.requestId(),
                            TREASURY,
                            request.amount(),
                            "player:22222222-2222-2222-2222-222222222222",
                            request.reason())));
            assertEquals(
                    request.operatorIdentity(),
                    database.permanentDestructionOperation(SERVICE.value(), request.requestId())
                            .operatorIdentity());

            assertEquals(1L, externalCalls.get());
        }
    }

    @Test
    void recoveryOnlyAdvancesPreparedOperationsOwnedByTheVerifiedServiceSession() {
        try (CivicDatabase database = database()) {
            authorize(database, SERVICE, "grant-maintenance-destruction");
            authorize(database, OTHER_SERVICE, "grant-other-destruction");
            seedIssuance(database, "seed-isolated-recovery", "mint-batch:isolated-recovery");

            ConfirmPermanentDestruction maintenance = new ConfirmPermanentDestruction(
                    SERVICE,
                    "maintenance-recovery",
                    TREASURY,
                    MoneyAmount.ofMinorUnits(100L),
                    "Maintenance recovery");
            ConfirmPermanentDestruction other = new ConfirmPermanentDestruction(
                    OTHER_SERVICE,
                    "other-recovery",
                    TREASURY,
                    MoneyAmount.ofMinorUnits(200L),
                    "Other service recovery");
            assertThrows(
                    IllegalStateException.class,
                    () -> coordinator(database, SERVICE, ignored -> {
                        throw new IllegalStateException("leave maintenance PREPARED");
                    }).confirm(maintenance));
            assertThrows(
                    IllegalStateException.class,
                    () -> coordinator(database, OTHER_SERVICE, ignored -> {
                        throw new IllegalStateException("leave other PREPARED");
                    }).confirm(other));

            AtomicLong recoveredMinorUnits = new AtomicLong();
            coordinator(database, SERVICE, destruction -> recoveredMinorUnits.addAndGet(
                            destruction.amount().minorUnits()))
                    .recoverAll();

            assertEquals(100L, recoveredMinorUnits.get());
            assertEquals(
                    "COMMITTED",
                    database.permanentDestructionOperation(
                                    SERVICE.value(), maintenance.requestId())
                            .state());
            assertEquals(
                    "PREPARED",
                    database.permanentDestructionOperation(
                                    OTHER_SERVICE.value(), other.requestId())
                            .state());
        }
    }

    private PermanentDestructionCoordinator coordinator(
            CivicDatabase database, ExternalPermanentDestructions external) {
        return coordinator(database, SERVICE, external);
    }

    private PermanentDestructionCoordinator coordinator(
            CivicDatabase database,
            ExternalPermanentDestructions external,
            PermanentDestructionProgressObserver progressObserver) {
        FiscalServiceSession session = FiscalTestSessions.open(
                database, SERVICE, "civiceconomy-tests");
        return PermanentDestructionCoordinator.authorized(
                database, external, session, CLOCK, progressObserver);
    }

    private PermanentDestructionCoordinator coordinator(
            CivicDatabase database,
            ServiceIdentity serviceIdentity,
            ExternalPermanentDestructions external) {
        FiscalServiceSession session = FiscalTestSessions.open(
                database, serviceIdentity, "civiceconomy-tests");
        return PermanentDestructionCoordinator.authorized(
                database, external, session, CLOCK);
    }

    private void seedIssuance(
            CivicDatabase database, String requestId, String externalReference) {
        database.confirmMonetarySupplyChange(
                UUID.randomUUID(),
                SERVICE.value(),
                requestId,
                MonetarySupplyChange.ISSUANCE.name(),
                1_000L,
                externalReference,
                "Seed confirmed issuance",
                NOW.toEpochMilli(),
                2_000L);
    }

    private void authorize(CivicDatabase database) {
        authorize(database, SERVICE, "grant-permanent-destruction");
    }

    private void authorize(
            CivicDatabase database, ServiceIdentity serviceIdentity, String grantRequestId) {
        FiscalAuthorization authorization = new FiscalAuthorization(database);
        authorization.register(new RegisterFiscalService(
                serviceIdentity, "civiceconomy-tests", "Permanent Destruction test service"));
        authorization.grant(new GrantFiscalCapability(
                new ServiceIdentity("test-admin"),
                grantRequestId,
                serviceIdentity,
                FiscalCapability.PERMANENT_DESTRUCTION,
                TREASURY,
                "Test exact-account destruction authority"));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("permanent-destruction.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("2ab5f10e-98b1-448d-9502-49efcb720c3d"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
