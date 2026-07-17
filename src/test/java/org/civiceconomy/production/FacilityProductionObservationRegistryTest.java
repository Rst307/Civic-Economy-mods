package org.civiceconomy.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.civiceconomy.persistence.StoredCreateRecipeCompletion;
import org.civiceconomy.persistence.StoredFacilityAccountingReceipt;
import org.civiceconomy.persistence.StoredFacilityProductionDecision;
import org.civiceconomy.persistence.StoredProductionInventoryChange;
import org.civiceconomy.territory.TerritoryClaimPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FacilityProductionObservationRegistryTest {
    private static final NationId NATION = new NationId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final UUID TEAM = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ACTOR = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID FACILITY = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID INTERFACE = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID COMPLETION = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID RECEIPT = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final ServiceIdentity SERVICE = new ServiceIdentity("observation-registry-test");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-01T00:00:10Z"), ZoneOffset.UTC);

    @TempDir Path temporaryDirectory;

    @Test
    void recordsOneImmutableCompletionReceiptAndDecisionAcrossReopen() {
        Path file = temporaryDirectory.resolve("production-observation.sqlite3");
        FacilityProductionObservation expected;
        try (CivicDatabase database = database(file)) {
            registerFacilityAndInterface(database);
            FacilityProductionObservationRegistry registry = registry(database);

            expected = registry.record(completion(), receipt());

            assertEquals(FacilityProductionDecisionKind.FACILITY_BASELINING,
                    expected.decision().kind());
            assertEquals(expected, registry.record(completion(), receipt()));
        }
        try (CivicDatabase reopened = database(file)) {
            FacilityProductionObservation restored =
                    registry(reopened).observation(COMPLETION);
            assertEquals(expected, restored);
        }
    }

    @Test
    void rejectsReplayWhenTheCreateRecipeCompletionChanges() {
        try (CivicDatabase database = database(
                temporaryDirectory.resolve("changed-completion.sqlite3"))) {
            registerFacilityAndInterface(database);
            FacilityProductionObservationRegistry registry = registry(database);
            registry.record(completion(), receipt());
            CreateRecipeCompletion changed = new CreateRecipeCompletion(
                    COMPLETION,
                    "6.0.6",
                    CreateMachineKind.MILLSTONE,
                    "create:milling/barley",
                    "minecraft:overworld",
                    2,
                    70,
                    2,
                    CLOCK.millis() - 2_000L,
                    completion().inventoryDelta());

            assertThrows(IllegalStateException.class, () -> registry.record(changed, receipt()));
        }
    }

    @Test
    void rejectsReplayWhenTheFacilityAccountingReceiptChanges() {
        try (CivicDatabase database = database(
                temporaryDirectory.resolve("changed-receipt.sqlite3"))) {
            registerFacilityAndInterface(database);
            FacilityProductionObservationRegistry registry = registry(database);
            registry.record(completion(), receipt());
            FacilityAccountingReceipt changed = new FacilityAccountingReceipt(
                    RECEIPT,
                    INTERFACE,
                    receipt().position(),
                    receipt().observedAtEpochMillis(),
                    List.of(change(4, "create:wheat_flour", 2)));

            assertThrows(IllegalStateException.class, () -> registry.record(completion(), changed));
        }
    }

    @Test
    void rejectsReplayWhenTheFacilityProductionDecisionChanges() {
        try (CivicDatabase database = database(
                temporaryDirectory.resolve("changed-decision.sqlite3"))) {
            registerFacilityAndInterface(database);
            registry(database).record(completion(), receipt());
            FacilityProductionObservationRegistry changedDecisionRegistry =
                    new FacilityProductionObservationRegistry(
                            database,
                            new FacilityProductionMatcher(
                                    database,
                                    (nation, team, claim) -> true,
                                    Set.of(),
                                    Duration.ofSeconds(5)),
                            CLOCK);

            assertThrows(
                    IllegalStateException.class,
                    () -> changedDecisionRegistry.record(completion(), receipt()));
        }
    }

    @Test
    void oneReceiptCannotBeConsumedByTwoRecipeCompletions() {
        try (CivicDatabase database = database(
                temporaryDirectory.resolve("receipt-reuse.sqlite3"))) {
            registerFacilityAndInterface(database);
            FacilityProductionObservationRegistry registry = registry(database);
            registry.record(completion(), receipt());
            CreateRecipeCompletion secondCompletion = new CreateRecipeCompletion(
                    UUID.fromString("88888888-8888-8888-8888-888888888888"),
                    completion().createVersion(),
                    completion().machineKind(),
                    completion().recipeId(),
                    completion().dimensionId(),
                    completion().blockX(),
                    completion().blockY(),
                    completion().blockZ(),
                    completion().observedAtEpochMillis(),
                    completion().inventoryDelta());

            assertThrows(
                    IllegalStateException.class,
                    () -> registry.record(secondCompletion, receipt()));
        }
    }

    @Test
    void failedObservationWriteLeavesNoPartialCompletionReceiptOrDecision() {
        try (CivicDatabase database = database(
                temporaryDirectory.resolve("atomic-failure.sqlite3"))) {
            StoredCreateRecipeCompletion completion = new StoredCreateRecipeCompletion(
                    COMPLETION,
                    "6.0.6",
                    "MILLSTONE",
                    "create:milling/wheat",
                    "minecraft:overworld",
                    2,
                    70,
                    2,
                    CLOCK.millis() - 2_000L);
            StoredFacilityAccountingReceipt receipt = new StoredFacilityAccountingReceipt(
                    RECEIPT,
                    UUID.fromString("99999999-9999-9999-9999-999999999999"),
                    "minecraft:overworld",
                    17,
                    72,
                    4,
                    CLOCK.millis() - 1_000L);

            assertThrows(IllegalStateException.class, () ->
                    database.recordFacilityProductionObservation(
                            completion,
                            List.of(storedChange(COMPLETION, "INPUT", 0, "minecraft:wheat")),
                            List.of(storedChange(
                                    COMPLETION, "OUTPUT", 0, "create:wheat_flour")),
                            receipt,
                            List.of(storedChange(
                                    RECEIPT, "RECEIPT", 4, "create:wheat_flour")),
                            new StoredFacilityProductionDecision(
                                    COMPLETION,
                                    null,
                                    null,
                                    RECEIPT,
                                    "UNMATCHED_FACILITY",
                                    "No Registered Facility matched",
                                    CLOCK.millis())));

            assertNull(database.createRecipeCompletion(COMPLETION));
            assertNull(database.facilityAccountingReceipt(RECEIPT));
            assertNull(database.facilityProductionDecision(COMPLETION));
        }
    }

    @Test
    void unmatchedFacilityDecisionPersistsAcrossReopen() {
        Path file = temporaryDirectory.resolve("unmatched-facility.sqlite3");
        FacilityProductionObservation expected;
        try (CivicDatabase database = database(file)) {
            registerFacilityAndInterface(database);
            CreateRecipeCompletion outsideFacility = new CreateRecipeCompletion(
                    COMPLETION,
                    completion().createVersion(),
                    completion().machineKind(),
                    completion().recipeId(),
                    completion().dimensionId(),
                    48,
                    completion().blockY(),
                    48,
                    completion().observedAtEpochMillis(),
                    completion().inventoryDelta());

            expected = registry(database).record(outsideFacility, receipt());

            assertEquals(FacilityProductionDecisionKind.UNMATCHED_FACILITY,
                    expected.decision().kind());
            assertNull(expected.decision().facilityId());
            assertNull(expected.decision().interfaceId());
            assertEquals(RECEIPT, expected.decision().receiptId());
        }
        try (CivicDatabase reopened = database(file)) {
            assertEquals(expected, registry(reopened).observation(COMPLETION));
        }
    }

    @Test
    void exactReplayPreservesTheOriginalDecisionTimestamp() {
        try (CivicDatabase database = database(
                temporaryDirectory.resolve("decision-time-replay.sqlite3"))) {
            registerFacilityAndInterface(database);
            FacilityProductionObservation original = registry(database).record(
                    completion(), receipt());
            FacilityProductionObservationRegistry laterRegistry =
                    new FacilityProductionObservationRegistry(
                            database,
                            new FacilityProductionMatcher(
                                    database,
                                    (nation, team, claim) -> true,
                                    Set.of(CreateMachineKind.MILLSTONE),
                                    Duration.ofSeconds(5)),
                            Clock.offset(CLOCK, Duration.ofMinutes(1)));

            assertEquals(original, laterRegistry.record(completion(), receipt()));
            assertEquals(CLOCK.instant(), original.decidedAt());
        }
    }

    private FacilityProductionObservationRegistry registry(CivicDatabase database) {
        FacilityProductionMatcher matcher = new FacilityProductionMatcher(
                database,
                (nation, team, claim) -> NATION.equals(nation) && TEAM.equals(team),
                Set.of(CreateMachineKind.MILLSTONE),
                Duration.ofSeconds(5));
        return new FacilityProductionObservationRegistry(database, matcher, CLOCK);
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
                        "Facility for observation registry"));
        new FacilityAccountingInterfaceRegistry(database, CLOCK).register(
                new RegisterFacilityAccountingInterface(
                        SERVICE,
                        "register-interface",
                        INTERFACE,
                        FACILITY,
                        new FacilityAccountingInterfacePosition(
                                "minecraft:overworld", 17, 72, 4),
                        ACTOR,
                        "Interface for observation registry"));
    }

    private static CreateRecipeCompletion completion() {
        return new CreateRecipeCompletion(
                COMPLETION,
                "6.0.6",
                CreateMachineKind.MILLSTONE,
                "create:milling/wheat",
                "minecraft:overworld",
                2,
                70,
                2,
                CLOCK.millis() - 2_000L,
                new MachineInventoryDelta(
                        List.of(change(0, "minecraft:wheat", 1)),
                        List.of(change(0, "create:wheat_flour", 1))));
    }

    private static FacilityAccountingReceipt receipt() {
        return new FacilityAccountingReceipt(
                RECEIPT,
                INTERFACE,
                new FacilityAccountingInterfacePosition(
                        "minecraft:overworld", 17, 72, 4),
                CLOCK.millis() - 1_000L,
                List.of(change(4, "create:wheat_flour", 1)));
    }

    private static MachineInventoryChange change(int slot, String itemId, int count) {
        return new MachineInventoryChange(
                slot, new ProductionStack(itemId, "components:{}", count));
    }

    private static StoredProductionInventoryChange storedChange(
            UUID sourceId, String role, int slot, String itemId) {
        return new StoredProductionInventoryChange(
                sourceId, role, slot, itemId, "components:{}", 1);
    }

    private static TerritoryClaimPosition claim(int chunkX, int chunkZ) {
        return new TerritoryClaimPosition("minecraft:overworld", chunkX, chunkZ);
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
