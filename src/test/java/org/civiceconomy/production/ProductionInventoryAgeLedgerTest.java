package org.civiceconomy.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductionInventoryAgeLedgerTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
    private static final ServiceIdentity SERVICE = new ServiceIdentity("inventory-age-test");
    private static final UUID NATION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TEAM = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ACTOR = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID FACILITY = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID INTERFACE = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID RECEIPT = UUID.fromString("66666666-6666-6666-6666-666666666666");

    @TempDir Path temporaryDirectory;

    @Test
    void persistsReceiptAgeAcrossRestartAndConsumesOneRequestOnly() {
        Path file = temporaryDirectory.resolve("inventory-age.sqlite3");
        ProductionInventoryAgeBatch expected;
        ProductionInventoryConsumptionRequest request = new ProductionInventoryConsumptionRequest(
                SERVICE,
                "sell-request",
                INTERFACE,
                "create:wheat_flour",
                "components:{}",
                3,
                CLOCK.millis() + 1_000L,
                "Sell three produced outputs");

        try (CivicDatabase database = database(file)) {
            registerInterface(database);
            ProductionInventoryAgeLedger ledger = new ProductionInventoryAgeLedger(database, CLOCK);
            expected = ledger.recordReceipt(receipt()).get(0);

            assertEquals(1_000L, expected.remainingCount());
            assertEquals(receipt().observedAtEpochMillis(),
                    expected.age().firstObservedAtEpochMillis());

            ProductionInventoryConsumption consumed = ledger.consume(request);
            assertEquals(3, consumed.consumedCount());
            assertEquals(997, ledger.batches(INTERFACE).get(0).remainingCount());
            assertEquals(consumed, ledger.consume(request));
            assertThrows(IllegalStateException.class, () -> ledger.consume(
                    new ProductionInventoryConsumptionRequest(
                            SERVICE,
                            "sell-request",
                            INTERFACE,
                            request.itemId(),
                            request.componentFingerprint(),
                            4,
                            request.consumedAtEpochMillis(),
                            request.reason())));
            assertThrows(IllegalStateException.class, () -> ledger.recordReceipt(
                    receipt(RECEIPT, receipt().observedAtEpochMillis(), 999)));
        }

        try (CivicDatabase reopened = database(file)) {
            ProductionInventoryAgeLedger ledger = new ProductionInventoryAgeLedger(reopened, CLOCK);
            assertEquals(expected.withRemainingCount(997), ledger.batches(INTERFACE).get(0));
            assertEquals(997, ledger.batches(INTERFACE).get(0).remainingCount());
        }
    }

    @Test
    void consumesOldestInventoryBatchBeforeNewerInventory() {
        try (CivicDatabase database = database(
                temporaryDirectory.resolve("inventory-age-fifo.sqlite3"))) {
            registerInterface(database);
            ProductionInventoryAgeLedger ledger = new ProductionInventoryAgeLedger(database, CLOCK);
            ledger.recordReceipt(receipt(RECEIPT, CLOCK.millis(), 2));
            UUID newerReceipt = UUID.fromString("77777777-7777-7777-7777-777777777777");
            ledger.recordReceipt(receipt(newerReceipt, CLOCK.millis() + 1_000L, 3));

            ledger.consume(new ProductionInventoryConsumptionRequest(
                    SERVICE,
                    "fifo-request",
                    INTERFACE,
                    "create:wheat_flour",
                    "components:{}",
                    3,
                    CLOCK.millis() + 2_000L,
                    "Export oldest output first"));

            List<ProductionInventoryAgeBatch> batches = ledger.batches(INTERFACE);
            assertEquals(0, batches.get(0).remainingCount());
            assertEquals(newerReceipt, batches.get(1).receiptId());
            assertEquals(2, batches.get(1).remainingCount());
        }
    }

    @Test
    void exportUsesServerObservedStackAndReplaysWithoutASecondExternalEffect() {
        try (CivicDatabase database = database(
                temporaryDirectory.resolve("inventory-export.sqlite3"))) {
            registerInterface(database);
            ProductionInventoryAgeLedger ledger = new ProductionInventoryAgeLedger(database, CLOCK);
            ledger.recordReceipt(receipt(RECEIPT, CLOCK.millis(), 4));
            AtomicInteger externalCalls = new AtomicInteger();
            ProductionInventoryExportCoordinator coordinator =
                    new ProductionInventoryExportCoordinator(
                            database,
                            CLOCK,
                            request -> {
                                externalCalls.incrementAndGet();
                                return new ProductionStack(
                                        "create:wheat_flour", "components:{}", request.count());
                            });
            ProductionInventoryExportRequest request = new ProductionInventoryExportRequest(
                    SERVICE,
                    "export-request",
                    INTERFACE,
                    ACTOR,
                    4,
                    3,
                    ProductionInventoryExportKind.EXPORT,
                    "trusted-export-terminal",
                    CLOCK.millis() + 1_000L,
                    "Export produced goods");

            ProductionInventoryExport exported = coordinator.export(request);

            assertEquals(1, externalCalls.get());
            assertEquals("create:wheat_flour", exported.exportedStack().itemId());
            assertEquals(3, exported.exportedStack().count());
            assertEquals(3, exported.consumption().consumedCount());
            assertEquals(exported, coordinator.export(request));
            assertEquals(1, externalCalls.get());
            assertEquals(1, ledger.batches(INTERFACE).get(0).remainingCount());
        }
    }

    @Test
    void durableExportLineageResolvesItsSourceReceipt() {
        try (CivicDatabase database = database(
                temporaryDirectory.resolve("inventory-export-lineage.sqlite3"))) {
            registerInterface(database);
            ProductionInventoryAgeLedger ledger = new ProductionInventoryAgeLedger(database, CLOCK);
            ledger.recordReceipt(receipt(RECEIPT, CLOCK.millis(), 4));
            ProductionInventoryExportCoordinator coordinator =
                    new ProductionInventoryExportCoordinator(
                            database,
                            CLOCK,
                            request -> new ProductionStack(
                                    "create:wheat_flour", "components:{}", request.count()));
            ProductionInventoryExport exported = coordinator.export(
                    new ProductionInventoryExportRequest(
                            SERVICE,
                            "lineage-request",
                            INTERFACE,
                            ACTOR,
                            4,
                            2,
                            ProductionInventoryExportKind.EXPORT,
                            "trusted-export-terminal",
                            CLOCK.millis() + 1_000L,
                            "Export two produced goods"));

            assertEquals(
                    List.of(RECEIPT),
                    new ProductionInventoryExportLineageRegistry(database)
                            .lineage(exported.exportId())
                            .sourceReceiptIds());
        }
    }

    private static FacilityAccountingReceipt receipt() {
        return receipt(RECEIPT, CLOCK.millis(), 1_000);
    }

    private static FacilityAccountingReceipt receipt(UUID receiptId, long observedAt, int count) {
        return new FacilityAccountingReceipt(
                receiptId,
                INTERFACE,
                new FacilityAccountingInterfacePosition(
                        "minecraft:overworld", 17, 72, 4),
                observedAt,
                List.of(new MachineInventoryChange(
                        4, new ProductionStack("create:wheat_flour", "components:{}", count))));
    }

    private void registerInterface(CivicDatabase database) {
        database.registerNation(
                new NationId(NATION).value(), SERVICE.value(), "nation", TEAM, CLOCK.millis() - 1L);
        new RegisteredFacilityRegistry(
                        database,
                        (nation, team, claim) ->
                                new NationId(NATION).equals(nation) && TEAM.equals(team),
                        CLOCK,
                        4)
                .register(new RegisterFacility(
                        SERVICE,
                        "register-facility",
                        FACILITY,
                        new NationId(NATION),
                        TEAM,
                        new FacilityCorePosition("minecraft:overworld", 1, 70, 1),
                        List.of(
                                new org.civiceconomy.territory.TerritoryClaimPosition(
                                        "minecraft:overworld", 0, 0),
                                new org.civiceconomy.territory.TerritoryClaimPosition(
                                        "minecraft:overworld", 1, 0)),
                        ACTOR,
                        "Facility for inventory age"));
        new FacilityAccountingInterfaceRegistry(database, CLOCK).register(
                new RegisterFacilityAccountingInterface(
                        SERVICE,
                        "register-interface",
                        INTERFACE,
                        FACILITY,
                        new FacilityAccountingInterfacePosition(
                                "minecraft:overworld", 17, 72, 4),
                        ACTOR,
                        "Interface for inventory age"));
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
