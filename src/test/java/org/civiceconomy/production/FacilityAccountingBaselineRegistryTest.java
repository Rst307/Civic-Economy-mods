package org.civiceconomy.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.civiceconomy.territory.TerritoryClaimPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FacilityAccountingBaselineRegistryTest {
    private static final NationId NATION = new NationId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final UUID TEAM = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ACTOR = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID FACILITY = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID INTERFACE = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID BASELINE = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final ServiceIdentity SERVICE = new ServiceIdentity("baseline-registry-test");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);

    @TempDir Path temporaryDirectory;

    @Test
    void capturesAuthoritativeMachinesInterfaceAndStartingInventoryAcrossReopen() {
        Path file = temporaryDirectory.resolve("facility-baseline.sqlite3");
        FacilityAccountingBaseline expected;
        try (CivicDatabase database = database(file)) {
            registerFacilityAndInterface(database);
            FacilityAccountingBaselineRegistry registry = new FacilityAccountingBaselineRegistry(
                    database,
                    (nation, team, claim) -> NATION.equals(nation) && TEAM.equals(team),
                    (facility, accountingInterface) -> new FacilityAccountingBaselineSnapshot(
                            "6.0.6",
                            List.of(new FacilityBaselineMachine(
                                    CreateMachineKind.MILLSTONE,
                                    new FacilityMachinePosition(
                                            "minecraft:overworld", 2, 70, 2))),
                            List.of(new MachineInventoryChange(
                                    4,
                                    new ProductionStack(
                                            "create:wheat_flour", "components:{}", 12)))),
                    CLOCK);

            expected = registry.capture(new CaptureFacilityAccountingBaseline(
                    SERVICE,
                    "capture-baseline",
                    BASELINE,
                    FACILITY,
                    ACTOR,
                    "Capture exact fixed machines and starting inventory"));

            assertEquals(FacilityAccountingBaselineState.CAPTURED, expected.state());
            assertEquals(INTERFACE, expected.interfaceId());
            assertEquals(RegisteredFacilityState.BASELINING,
                    new RegisteredFacilityRegistry(
                                    database,
                                    (nation, team, claim) -> true,
                                    CLOCK,
                                    4)
                            .facility(FACILITY)
                            .state());
        }
        try (CivicDatabase reopened = database(file)) {
            FacilityAccountingBaseline restored = new FacilityAccountingBaselineRegistry(
                            reopened,
                            (nation, team, claim) -> true,
                            (facility, accountingInterface) -> {
                                throw new AssertionError("Reading a baseline must not recapture it");
                            },
                            CLOCK)
                    .baseline(FACILITY);

            assertEquals(expected, restored);
        }
    }

    @Test
    void exactCaptureReplayReturnsTheOriginalBaselineWithoutRecapturingTheWorld() {
        try (CivicDatabase database = database(
                temporaryDirectory.resolve("replay-facility-baseline.sqlite3"))) {
            registerFacilityAndInterface(database);
            AtomicInteger captures = new AtomicInteger();
            FacilityAccountingBaselineRegistry registry = new FacilityAccountingBaselineRegistry(
                    database,
                    (nation, team, claim) -> true,
                    (facility, accountingInterface) -> {
                        if (captures.incrementAndGet() != 1) {
                            throw new AssertionError(
                                    "Exact capture replay must not read a new world snapshot");
                        }
                        return snapshot(12);
                    },
                    CLOCK);
            CaptureFacilityAccountingBaseline request = new CaptureFacilityAccountingBaseline(
                    SERVICE,
                    "capture-replay",
                    BASELINE,
                    FACILITY,
                    ACTOR,
                    "Capture one immutable baseline");

            FacilityAccountingBaseline first = registry.capture(request);
            FacilityAccountingBaseline replay = registry.capture(request);

            assertEquals(first, replay);
            assertEquals(1, captures.get());
        }
    }

    @Test
    void captureReplayRejectsAnyChangedCallerPayload() {
        try (CivicDatabase database = database(
                temporaryDirectory.resolve("conflicting-capture-replay.sqlite3"))) {
            registerFacilityAndInterface(database);
            FacilityAccountingBaselineRegistry registry = new FacilityAccountingBaselineRegistry(
                    database,
                    (nation, team, claim) -> true,
                    (facility, accountingInterface) -> snapshot(12),
                    CLOCK);
            CaptureFacilityAccountingBaseline original = new CaptureFacilityAccountingBaseline(
                    SERVICE,
                    "immutable-capture",
                    BASELINE,
                    FACILITY,
                    ACTOR,
                    "Immutable capture payload");
            FacilityAccountingBaseline captured = registry.capture(original);

            assertThrows(IllegalStateException.class, () -> registry.capture(
                    new CaptureFacilityAccountingBaseline(
                            SERVICE,
                            original.requestId(),
                            UUID.fromString("77777777-7777-7777-7777-777777777777"),
                            FACILITY,
                            ACTOR,
                            original.reason())));
            assertThrows(IllegalStateException.class, () -> registry.capture(
                    new CaptureFacilityAccountingBaseline(
                            SERVICE,
                            original.requestId(),
                            BASELINE,
                            UUID.fromString("88888888-8888-8888-8888-888888888888"),
                            ACTOR,
                            original.reason())));
            assertThrows(IllegalStateException.class, () -> registry.capture(
                    new CaptureFacilityAccountingBaseline(
                            SERVICE,
                            original.requestId(),
                            BASELINE,
                            FACILITY,
                            UUID.fromString("99999999-9999-9999-9999-999999999999"),
                            original.reason())));
            assertThrows(IllegalStateException.class, () -> registry.capture(
                    new CaptureFacilityAccountingBaseline(
                            SERVICE,
                            original.requestId(),
                            BASELINE,
                            FACILITY,
                            ACTOR,
                            "Changed capture reason")));
            assertEquals(captured, registry.baseline(FACILITY));
        }
    }

    @Test
    void activationRevalidatesBindingsAndAtomicallyActivatesTheFacility() {
        Path file = temporaryDirectory.resolve("activate-facility-baseline.sqlite3");
        try (CivicDatabase database = database(file)) {
            registerFacilityAndInterface(database);
            FacilityAccountingBaselineRegistry captureRegistry =
                    new FacilityAccountingBaselineRegistry(
                            database,
                            (nation, team, claim) -> true,
                            (facility, accountingInterface) -> snapshot(12),
                            CLOCK);
            captureRegistry.capture(new CaptureFacilityAccountingBaseline(
                    SERVICE,
                    "capture-for-activation",
                    BASELINE,
                    FACILITY,
                    ACTOR,
                    "Capture baseline before activation"));
            Clock activationClock = Clock.offset(CLOCK, Duration.ofMinutes(1));
            FacilityAccountingBaselineRegistry activationRegistry =
                    new FacilityAccountingBaselineRegistry(
                            database,
                            (nation, team, claim) -> true,
                            (facility, accountingInterface) -> snapshot(19),
                            activationClock);

            FacilityAccountingBaseline activated = activationRegistry.activate(
                    new ActivateFacilityAccountingBaseline(
                            SERVICE,
                            "activate-baseline",
                            FACILITY,
                            ACTOR,
                            "Activate exact baseline bindings"));

            assertEquals(FacilityAccountingBaselineState.ACTIVE, activated.state());
            assertEquals(activationClock.instant(), activated.activatedAt());
            assertEquals(12, activated.startingInventory().getFirst().stack().count());
            assertEquals(RegisteredFacilityState.ACTIVE,
                    new RegisteredFacilityRegistry(
                                    database,
                                    (nation, team, claim) -> true,
                                    CLOCK,
                                    4)
                            .facility(FACILITY)
                            .state());
        }
    }

    @Test
    void exactActivationReplayReturnsTheCommittedResultWithoutRecheckingTheWorld() {
        try (CivicDatabase database = database(
                temporaryDirectory.resolve("activation-replay.sqlite3"))) {
            registerFacilityAndInterface(database);
            FacilityAccountingBaselineRegistry initialRegistry =
                    new FacilityAccountingBaselineRegistry(
                            database,
                            (nation, team, claim) -> true,
                            (facility, accountingInterface) -> snapshot(12),
                            CLOCK);
            initialRegistry.capture(new CaptureFacilityAccountingBaseline(
                    SERVICE,
                    "capture-for-replay",
                    BASELINE,
                    FACILITY,
                    ACTOR,
                    "Capture before activation replay"));
            ActivateFacilityAccountingBaseline request = new ActivateFacilityAccountingBaseline(
                    SERVICE,
                    "activate-replay",
                    FACILITY,
                    ACTOR,
                    "Activate once and replay");
            FacilityAccountingBaseline activated = initialRegistry.activate(request);
            FacilityAccountingBaselineRegistry replayRegistry =
                    new FacilityAccountingBaselineRegistry(
                            database,
                            (nation, team, claim) -> false,
                            (facility, accountingInterface) -> {
                                throw new AssertionError(
                                        "Exact activation replay must not recapture the world");
                            },
                            Clock.offset(CLOCK, Duration.ofMinutes(2)));

            FacilityAccountingBaseline replay = replayRegistry.activate(request);

            assertEquals(activated, replay);
        }
    }

    @Test
    void activationReplayRejectsAnyChangedCallerPayload() {
        try (CivicDatabase database = database(
                temporaryDirectory.resolve("conflicting-activation-replay.sqlite3"))) {
            registerFacilityAndInterface(database);
            FacilityAccountingBaselineRegistry initialRegistry =
                    new FacilityAccountingBaselineRegistry(
                            database,
                            (nation, team, claim) -> true,
                            (facility, accountingInterface) -> snapshot(12),
                            CLOCK);
            initialRegistry.capture(new CaptureFacilityAccountingBaseline(
                    SERVICE,
                    "capture-before-conflicting-activation",
                    BASELINE,
                    FACILITY,
                    ACTOR,
                    "Capture before conflicting activation"));
            ActivateFacilityAccountingBaseline original =
                    new ActivateFacilityAccountingBaseline(
                            SERVICE,
                            "immutable-activation",
                            FACILITY,
                            ACTOR,
                            "Immutable activation payload");
            FacilityAccountingBaseline activated = initialRegistry.activate(original);
            FacilityAccountingBaselineRegistry replayRegistry =
                    new FacilityAccountingBaselineRegistry(
                            database,
                            (nation, team, claim) -> false,
                            (facility, accountingInterface) -> {
                                throw new AssertionError(
                                        "Conflicting activation replay must fail before world reads");
                            },
                            Clock.offset(CLOCK, Duration.ofMinutes(2)));

            assertThrows(IllegalStateException.class, () -> replayRegistry.activate(
                    new ActivateFacilityAccountingBaseline(
                            SERVICE,
                            original.requestId(),
                            UUID.fromString("77777777-7777-7777-7777-777777777777"),
                            ACTOR,
                            original.reason())));
            assertThrows(IllegalStateException.class, () -> replayRegistry.activate(
                    new ActivateFacilityAccountingBaseline(
                            SERVICE,
                            original.requestId(),
                            FACILITY,
                            UUID.fromString("88888888-8888-8888-8888-888888888888"),
                            original.reason())));
            assertThrows(IllegalStateException.class, () -> replayRegistry.activate(
                    new ActivateFacilityAccountingBaseline(
                            SERVICE,
                            original.requestId(),
                            FACILITY,
                            ACTOR,
                            "Changed activation reason")));
            assertEquals(activated, replayRegistry.baseline(FACILITY));
        }
    }

    @Test
    void activeBaselineAndFacilitySurviveDatabaseReopen() {
        Path file = temporaryDirectory.resolve("active-baseline-reopen.sqlite3");
        FacilityAccountingBaseline activated;
        try (CivicDatabase database = database(file)) {
            registerFacilityAndInterface(database);
            FacilityAccountingBaselineRegistry registry = new FacilityAccountingBaselineRegistry(
                    database,
                    (nation, team, claim) -> true,
                    (facility, accountingInterface) -> snapshot(12),
                    CLOCK);
            registry.capture(new CaptureFacilityAccountingBaseline(
                    SERVICE,
                    "capture-before-reopen",
                    BASELINE,
                    FACILITY,
                    ACTOR,
                    "Capture before reopen"));
            activated = registry.activate(new ActivateFacilityAccountingBaseline(
                    SERVICE,
                    "activate-before-reopen",
                    FACILITY,
                    ACTOR,
                    "Activate before reopen"));
        }

        try (CivicDatabase reopened = database(file)) {
            FacilityAccountingBaseline restored = new FacilityAccountingBaselineRegistry(
                            reopened,
                            (nation, team, claim) -> false,
                            (facility, accountingInterface) -> {
                                throw new AssertionError(
                                        "Reopen reads must not capture a world snapshot");
                            },
                            Clock.offset(CLOCK, Duration.ofDays(1)))
                    .baseline(FACILITY);
            RegisteredFacility restoredFacility = new RegisteredFacilityRegistry(
                            reopened,
                            (nation, team, claim) -> false,
                            CLOCK,
                            4)
                    .facility(FACILITY);

            assertEquals(activated, restored);
            assertEquals(FacilityAccountingBaselineState.ACTIVE, restored.state());
            assertEquals(RegisteredFacilityState.ACTIVE, restoredFacility.state());
        }
    }

    @Test
    void failedActivationTransactionLeavesNoAuditOrHalfActivatedState() throws Exception {
        Path file = temporaryDirectory.resolve("failed-baseline-activation.sqlite3");
        try (CivicDatabase database = database(file)) {
            registerFacilityAndInterface(database);
            FacilityAccountingBaselineRegistry registry = new FacilityAccountingBaselineRegistry(
                    database,
                    (nation, team, claim) -> true,
                    (facility, accountingInterface) -> snapshot(12),
                    CLOCK);
            registry.capture(new CaptureFacilityAccountingBaseline(
                    SERVICE,
                    "capture-before-failed-activation",
                    BASELINE,
                    FACILITY,
                    ACTOR,
                    "Capture before injected activation failure"));
            ActivateFacilityAccountingBaseline activation =
                    new ActivateFacilityAccountingBaseline(
                            SERVICE,
                            "retry-failed-activation",
                            FACILITY,
                            ACTOR,
                            "Retry the exact failed activation");
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                    var statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TRIGGER reject_facility_activation
                        BEFORE UPDATE OF state ON registered_facility
                        WHEN NEW.state = 'ACTIVE'
                        BEGIN
                            SELECT RAISE(ABORT, 'injected activation failure');
                        END
                        """);
            }

            assertThrows(IllegalStateException.class, () -> registry.activate(activation));
            assertEquals(FacilityAccountingBaselineState.CAPTURED,
                    registry.baseline(FACILITY).state());
            assertEquals(RegisteredFacilityState.BASELINING,
                    new RegisteredFacilityRegistry(
                                    database,
                                    (nation, team, claim) -> true,
                                    CLOCK,
                                    4)
                            .facility(FACILITY)
                            .state());

            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                    var statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER reject_facility_activation");
            }
            FacilityAccountingBaseline retried = registry.activate(activation);

            assertEquals(FacilityAccountingBaselineState.ACTIVE, retried.state());
            assertEquals(RegisteredFacilityState.ACTIVE,
                    new RegisteredFacilityRegistry(
                                    database,
                                    (nation, team, claim) -> true,
                                    CLOCK,
                                    4)
                            .facility(FACILITY)
                            .state());
        }
    }

    @Test
    void activationRejectsChangedFixedMachineBindingsWithoutChangingState() {
        try (CivicDatabase database = database(
                temporaryDirectory.resolve("changed-baseline-machine.sqlite3"))) {
            registerFacilityAndInterface(database);
            FacilityAccountingBaselineRegistry captureRegistry =
                    new FacilityAccountingBaselineRegistry(
                            database,
                            (nation, team, claim) -> true,
                            (facility, accountingInterface) -> snapshot(12),
                            CLOCK);
            captureRegistry.capture(new CaptureFacilityAccountingBaseline(
                    SERVICE,
                    "capture-before-machine-change",
                    BASELINE,
                    FACILITY,
                    ACTOR,
                    "Capture before machine moves"));
            FacilityAccountingBaselineRegistry changedBindingRegistry =
                    new FacilityAccountingBaselineRegistry(
                            database,
                            (nation, team, claim) -> true,
                            (facility, accountingInterface) -> new FacilityAccountingBaselineSnapshot(
                                    "6.0.6",
                                    List.of(new FacilityBaselineMachine(
                                            CreateMachineKind.MILLSTONE,
                                            new FacilityMachinePosition(
                                                    "minecraft:overworld", 3, 70, 2))),
                                    List.of()),
                            Clock.offset(CLOCK, Duration.ofMinutes(1)));

            assertThrows(SecurityException.class, () -> changedBindingRegistry.activate(
                    new ActivateFacilityAccountingBaseline(
                            SERVICE,
                            "reject-moved-machine",
                            FACILITY,
                            ACTOR,
                            "Reject changed fixed machine")));
            assertEquals(FacilityAccountingBaselineState.CAPTURED,
                    changedBindingRegistry.baseline(FACILITY).state());
            assertEquals(RegisteredFacilityState.BASELINING,
                    new RegisteredFacilityRegistry(
                                    database,
                                    (nation, team, claim) -> true,
                                    CLOCK,
                                    4)
                            .facility(FACILITY)
                            .state());
        }
    }

    @Test
    void activationRejectsTerritoryLossBeforeChangingEitherState() {
        try (CivicDatabase database = database(
                temporaryDirectory.resolve("baseline-territory-loss.sqlite3"))) {
            registerFacilityAndInterface(database);
            FacilityAccountingBaselineRegistry captureRegistry =
                    new FacilityAccountingBaselineRegistry(
                            database,
                            (nation, team, claim) -> true,
                            (facility, accountingInterface) -> snapshot(12),
                            CLOCK);
            captureRegistry.capture(new CaptureFacilityAccountingBaseline(
                    SERVICE,
                    "capture-before-territory-loss",
                    BASELINE,
                    FACILITY,
                    ACTOR,
                    "Capture before territory loss"));
            FacilityAccountingBaselineRegistry lostTerritoryRegistry =
                    new FacilityAccountingBaselineRegistry(
                            database,
                            (nation, team, claim) -> false,
                            (facility, accountingInterface) -> snapshot(12),
                            Clock.offset(CLOCK, Duration.ofMinutes(1)));

            assertThrows(SecurityException.class, () -> lostTerritoryRegistry.activate(
                    new ActivateFacilityAccountingBaseline(
                            SERVICE,
                            "reject-territory-loss",
                            FACILITY,
                            ACTOR,
                            "Reject activation after territory loss")));
            assertEquals(FacilityAccountingBaselineState.CAPTURED,
                    lostTerritoryRegistry.baseline(FACILITY).state());
            assertEquals(RegisteredFacilityState.BASELINING,
                    new RegisteredFacilityRegistry(
                                    database,
                                    (nation, team, claim) -> true,
                                    CLOCK,
                                    4)
                            .facility(FACILITY)
                            .state());
        }
    }

    private void registerFacilityAndInterface(CivicDatabase database) {
        database.registerNation(NATION.value(), SERVICE.value(), "nation", TEAM, CLOCK.millis() - 1L);
        new RegisteredFacilityRegistry(
                        database,
                        (nation, team, claim) -> NATION.equals(nation) && TEAM.equals(team),
                        CLOCK,
                        4)
                .register(new RegisterFacility(
                        SERVICE,
                        "register-facility",
                        FACILITY,
                        NATION,
                        TEAM,
                        new FacilityCorePosition("minecraft:overworld", 1, 70, 1),
                        List.of(claim(0, 0), claim(1, 0)),
                        ACTOR,
                        "Facility for baseline capture"));
        new FacilityAccountingInterfaceRegistry(database, CLOCK).register(
                new RegisterFacilityAccountingInterface(
                        SERVICE,
                        "register-interface",
                        INTERFACE,
                        FACILITY,
                        new FacilityAccountingInterfacePosition(
                                "minecraft:overworld", 17, 72, 4),
                        ACTOR,
                        "Interface for baseline capture"));
    }

    private static TerritoryClaimPosition claim(int chunkX, int chunkZ) {
        return new TerritoryClaimPosition("minecraft:overworld", chunkX, chunkZ);
    }

    private static FacilityAccountingBaselineSnapshot snapshot(int inventoryCount) {
        return new FacilityAccountingBaselineSnapshot(
                "6.0.6",
                List.of(new FacilityBaselineMachine(
                        CreateMachineKind.MILLSTONE,
                        new FacilityMachinePosition("minecraft:overworld", 2, 70, 2))),
                List.of(new MachineInventoryChange(
                        4,
                        new ProductionStack(
                                "create:wheat_flour", "components:{}", inventoryCount))));
    }

    private CivicDatabase database(Path file) {
        return CivicDatabase.open(
                file,
                new DatabaseIdentity(
                        UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
