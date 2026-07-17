package org.civiceconomy.production;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import org.civiceconomy.territory.TerritoryClaimPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FacilityProductionMatcherTest {
    private static final NationId NATION = new NationId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final UUID TEAM = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ACTOR = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID FACILITY = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID INTERFACE = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID COMPLETION = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID RECEIPT = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final ServiceIdentity SERVICE = new ServiceIdentity("production-matcher-test");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @TempDir Path temporaryDirectory;

    @Test
    void exactMillstoneCompletionAndInterfaceReceiptRemainExcludedDuringBaseline() {
        try (CivicDatabase database = database(temporaryDirectory.resolve("matcher.sqlite3"))) {
            registerFacilityAndInterface(database);
            FacilityProductionMatcher matcher = new FacilityProductionMatcher(
                    database,
                    (nation, team, claim) -> NATION.equals(nation) && TEAM.equals(team),
                    Set.of(CreateMachineKind.MILLSTONE),
                    Duration.ofSeconds(5));

            FacilityProductionDecision decision = matcher.match(
                    completion(),
                    new FacilityAccountingReceipt(
                            RECEIPT,
                            INTERFACE,
                            new FacilityAccountingInterfacePosition(
                                    "minecraft:overworld", 17, 72, 4),
                            CLOCK.millis() + 1_000L,
                            List.of(change(4, "create:wheat_flour", 1))));

            assertEquals(FacilityProductionDecisionKind.FACILITY_BASELINING, decision.kind());
            assertEquals(FACILITY, decision.facilityId());
            assertEquals(INTERFACE, decision.interfaceId());
            assertEquals(RECEIPT, decision.receiptId());
        }
    }

    @Test
    void territoryLossIsReportedBeforeAnyProductionCanBeIncluded() {
        try (CivicDatabase database = database(temporaryDirectory.resolve("territory-loss.sqlite3"))) {
            registerFacilityAndInterface(database);
            FacilityProductionMatcher matcher = new FacilityProductionMatcher(
                    database,
                    (nation, team, claim) -> false,
                    Set.of(CreateMachineKind.MILLSTONE),
                    Duration.ofSeconds(5));

            FacilityProductionDecision decision = matcher.match(
                    completion(), exactReceipt());

            assertEquals(
                    FacilityProductionDecisionKind.FACILITY_TERRITORY_INEFFECTIVE,
                    decision.kind());
        }
    }

    @Test
    void differentInterfaceOutputIsExplicitlyUnmatched() {
        try (CivicDatabase database = database(temporaryDirectory.resolve("unmatched.sqlite3"))) {
            registerFacilityAndInterface(database);
            FacilityProductionMatcher matcher = new FacilityProductionMatcher(
                    database,
                    (nation, team, claim) -> true,
                    Set.of(CreateMachineKind.MILLSTONE),
                    Duration.ofSeconds(5));
            FacilityAccountingReceipt differentOutput = new FacilityAccountingReceipt(
                    RECEIPT,
                    INTERFACE,
                    exactReceipt().position(),
                    exactReceipt().observedAtEpochMillis(),
                    List.of(change(4, "minecraft:bone_meal", 1)));

            FacilityProductionDecision decision = matcher.match(completion(), differentOutput);

            assertEquals(
                    FacilityProductionDecisionKind.UNMATCHED_INTERFACE_RECEIPT,
                    decision.kind());
        }
    }

    @Test
    void machineOutsideTheVersionPinnedWhitelistIsExplicitlyExcluded() {
        try (CivicDatabase database = database(temporaryDirectory.resolve("unsupported.sqlite3"))) {
            registerFacilityAndInterface(database);
            FacilityProductionMatcher matcher = new FacilityProductionMatcher(
                    database,
                    (nation, team, claim) -> true,
                    Set.of(),
                    Duration.ofSeconds(5));

            FacilityProductionDecision decision = matcher.match(completion(), exactReceipt());

            assertEquals(FacilityProductionDecisionKind.UNSUPPORTED_MACHINE, decision.kind());
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
                        "Facility for matcher"));
        new FacilityAccountingInterfaceRegistry(database, CLOCK).register(
                new RegisterFacilityAccountingInterface(
                        SERVICE,
                        "register-interface",
                        INTERFACE,
                        FACILITY,
                        new FacilityAccountingInterfacePosition(
                                "minecraft:overworld", 17, 72, 4),
                        ACTOR,
                        "Interface for matcher"));
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
                CLOCK.millis(),
                new MachineInventoryDelta(
                        List.of(change(0, "minecraft:wheat", 1)),
                        List.of(change(0, "create:wheat_flour", 1))));
    }

    private static FacilityAccountingReceipt exactReceipt() {
        return new FacilityAccountingReceipt(
                RECEIPT,
                INTERFACE,
                new FacilityAccountingInterfacePosition(
                        "minecraft:overworld", 17, 72, 4),
                CLOCK.millis() + 1_000L,
                List.of(change(4, "create:wheat_flour", 1)));
    }

    private static MachineInventoryChange change(int slot, String itemId, int count) {
        return new MachineInventoryChange(
                slot, new ProductionStack(itemId, "components:{}", count));
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
