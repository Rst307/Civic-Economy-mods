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
            assertEquals(400L, lcBalance.get());
            assertEquals(400L, database.cumulativeNetIssuanceMinorUnits());
            assertEquals(2, database.monetarySupplyEvents().size());
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
                    "Authorized exact account destruction");
            coordinator.confirm(request);
            assertThrows(
                    org.civiceconomy.fiscal.IdempotencyConflictException.class,
                    () -> coordinator.confirm(new ConfirmPermanentDestruction(
                            SERVICE,
                            request.requestId(),
                            TREASURY,
                            MoneyAmount.ofMinorUnits(101L),
                            request.reason())));

            assertEquals(1L, externalCalls.get());
        }
    }

    private PermanentDestructionCoordinator coordinator(
            CivicDatabase database, ExternalPermanentDestructions external) {
        FiscalServiceSession session = FiscalTestSessions.open(database, SERVICE, "civiceconomy-tests");
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
        FiscalAuthorization authorization = new FiscalAuthorization(database);
        authorization.register(new RegisterFiscalService(
                SERVICE, "civiceconomy-tests", "Maintenance test service"));
        authorization.grant(new GrantFiscalCapability(
                new ServiceIdentity("test-admin"),
                "grant-permanent-destruction",
                SERVICE,
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
