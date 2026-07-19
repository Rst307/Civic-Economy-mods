package org.civiceconomy.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductionChainContributionCalculatorTest {
    private static final Instant OBSERVED_AT = Instant.parse("2026-07-24T00:00:00Z");
    private static final UUID OBSERVATION =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID RECEIPT =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID FACILITY =
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID INTERFACE =
            UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID NATION =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TEAM =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ACTOR =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final ServiceIdentity SERVICE = new ServiceIdentity("chain-test");

    @TempDir
    Path temporaryDirectory;

    @Test
    void splitExportsOfOneReceiptContributeThatProductionSourceOnlyOnce() {
        try (CivicDatabase database = database()) {
            GlobalReferencePriceRegistry prices = new GlobalReferencePriceRegistry(
                    database, Clock.fixed(OBSERVED_AT.minusSeconds(1L), ZoneOffset.UTC));
            prices.schedule(new ScheduleGlobalReferencePrice(
                    new ServiceIdentity("chain-price-test"),
                    "wheat-price",
                    "test-actor",
                    "minecraft:wheat",
                    "components:{}",
                    10L,
                    OBSERVED_AT,
                    "Chain wheat price"));
            prices.schedule(new ScheduleGlobalReferencePrice(
                    new ServiceIdentity("chain-price-test"),
                    "flour-price",
                    "test-actor",
                    "create:wheat_flour",
                    "components:{}",
                    35L,
                    OBSERVED_AT,
                    "Chain flour price"));

            ProductionChainContributionAssessment assessment =
                    new ProductionChainContributionCalculator(
                            new ProductionValueAddedCalculator(prices))
                            .assess(
                                    List.of(observation()),
                                    List.of(
                                            lineage("first-export"),
                                            lineage("second-export")),
                                    OBSERVED_AT);

            assertEquals(15L, assessment.acceptedValueMinorUnits());
            assertEquals(1, assessment.acceptedObservationCount());
            assertEquals(1, assessment.sourceReceiptCount());
        }
    }

    @Test
    void missingDurableExportLineageFailsClosed() {
        try (CivicDatabase database = database()) {
            ProductionChainContributionCalculator calculator =
                    new ProductionChainContributionCalculator(
                            new ProductionValueAddedCalculator(
                                    new GlobalReferencePriceRegistry(
                                            database,
                                            Clock.fixed(
                                                    OBSERVED_AT.minusSeconds(1L),
                                                    ZoneOffset.UTC))));

            assertThrows(
                    IllegalStateException.class,
                    () -> calculator.assess(
                            List.of(observation()),
                            List.of(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")),
                            exportId -> null,
                            OBSERVED_AT));
        }
    }

    @Test
    void calculatorCanUseLineageReadFromTheRealExportTables() {
        try (CivicDatabase database = database()) {
            registerInterface(database);
            new ProductionInventoryAgeLedger(database, Clock.fixed(
                    OBSERVED_AT.minusSeconds(1L), ZoneOffset.UTC)).recordReceipt(
                            new FacilityAccountingReceipt(
                                    RECEIPT,
                                    INTERFACE,
                                    new FacilityAccountingInterfacePosition(
                                            "minecraft:overworld", 17, 72, 4),
                                    OBSERVED_AT.toEpochMilli(),
                                    List.of(change(4, "create:wheat_flour", 1))));
            ProductionInventoryExport exported = new ProductionInventoryExportCoordinator(
                    database,
                    Clock.fixed(OBSERVED_AT.minusSeconds(1L), ZoneOffset.UTC),
                    request -> new ProductionStack(
                            "create:wheat_flour", "components:{}", request.count()))
                    .export(new ProductionInventoryExportRequest(
                            SERVICE,
                            "durable-chain-export",
                            INTERFACE,
                            ACTOR,
                            4,
                            1,
                            ProductionInventoryExportKind.EXPORT,
                            "trusted-export-terminal",
                            OBSERVED_AT.toEpochMilli() + 1L,
                            "Export chain output"));

            GlobalReferencePriceRegistry prices = new GlobalReferencePriceRegistry(
                    database, Clock.fixed(OBSERVED_AT.minusSeconds(1L), ZoneOffset.UTC));
            prices.schedule(new ScheduleGlobalReferencePrice(
                    SERVICE,
                    "durable-chain-wheat-price",
                    "test-actor",
                    "minecraft:wheat",
                    "components:{}",
                    10L,
                    OBSERVED_AT,
                    "Durable chain wheat price"));
            prices.schedule(new ScheduleGlobalReferencePrice(
                    SERVICE,
                    "durable-chain-flour-price",
                    "test-actor",
                    "create:wheat_flour",
                    "components:{}",
                    35L,
                    OBSERVED_AT,
                    "Durable chain flour price"));

            ProductionChainContributionAssessment assessment =
                    new ProductionChainContributionCalculator(
                            new ProductionValueAddedCalculator(prices))
                            .assess(
                                    List.of(observation()),
                                    List.of(exported.exportId()),
                                    new ProductionInventoryExportLineageRegistry(database),
                                    OBSERVED_AT);

            assertEquals(15L, assessment.acceptedValueMinorUnits());
            assertEquals(1, assessment.sourceReceiptCount());
            assertEquals(0, assessment.unmatchedSourceReceiptCount());
        }
    }

    private ProductionInventoryExportLineage lineage(String exportId) {
        return new ProductionInventoryExportLineage(
                UUID.nameUUIDFromBytes(exportId.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                List.of(RECEIPT));
    }

    private static FacilityProductionObservation observation() {
        CreateRecipeCompletion completion = new CreateRecipeCompletion(
                OBSERVATION,
                "6.0.6",
                CreateMachineKind.MILLSTONE,
                "create:milling/wheat",
                "minecraft:overworld",
                2,
                70,
                2,
                OBSERVED_AT.toEpochMilli(),
                new MachineInventoryDelta(
                        List.of(change(0, "minecraft:wheat", 2)),
                        List.of(change(0, "create:wheat_flour", 1))));
        FacilityAccountingReceipt receipt = new FacilityAccountingReceipt(
                RECEIPT,
                INTERFACE,
                new FacilityAccountingInterfacePosition(
                        "minecraft:overworld", 17, 72, 4),
                OBSERVED_AT.toEpochMilli(),
                List.of(change(4, "create:wheat_flour", 1)));
        return new FacilityProductionObservation(
                completion,
                receipt,
                new FacilityProductionDecision(
                        OBSERVATION,
                        FACILITY,
                        INTERFACE,
                        RECEIPT,
                        FacilityProductionDecisionKind.INCLUDED,
                        "Accepted chain evidence"),
                OBSERVED_AT);
    }

    private static MachineInventoryChange change(int slot, String itemId, int count) {
        return new MachineInventoryChange(
                slot, new ProductionStack(itemId, "components:{}", count));
    }

    private void registerInterface(CivicDatabase database) {
        database.registerNation(
                new org.civiceconomy.nation.NationId(NATION).value(),
                SERVICE.value(), "chain nation", TEAM,
                OBSERVED_AT.minusSeconds(2L).toEpochMilli());
        new RegisteredFacilityRegistry(
                database,
                (nation, team, claim) -> true,
                Clock.fixed(OBSERVED_AT.minusSeconds(1L), ZoneOffset.UTC),
                4)
                .register(new RegisterFacility(
                        SERVICE,
                        "chain-facility",
                        FACILITY,
                        new org.civiceconomy.nation.NationId(NATION),
                        TEAM,
                        new FacilityCorePosition("minecraft:overworld", 1, 70, 1),
                        List.of(
                                new org.civiceconomy.territory.TerritoryClaimPosition(
                                        "minecraft:overworld", 0, 0),
                                new org.civiceconomy.territory.TerritoryClaimPosition(
                                        "minecraft:overworld", 1, 0)),
                        ACTOR,
                        "Chain facility"));
        new FacilityAccountingInterfaceRegistry(
                database, Clock.fixed(OBSERVED_AT.minusSeconds(1L), ZoneOffset.UTC))
                .register(new RegisterFacilityAccountingInterface(
                        SERVICE,
                        "chain-interface",
                        INTERFACE,
                        FACILITY,
                        new FacilityAccountingInterfacePosition(
                                "minecraft:overworld", 17, 72, 4),
                        ACTOR,
                        "Chain interface"));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("production-chain.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
