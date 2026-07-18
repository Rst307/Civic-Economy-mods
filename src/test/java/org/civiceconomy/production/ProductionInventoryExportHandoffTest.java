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

class ProductionInventoryExportHandoffTest {
    private static final Instant NOW = Instant.parse("2026-07-25T00:00:00Z");
    private static final ServiceIdentity SERVICE = new ServiceIdentity("handoff-test");
    private static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SOURCE_FACILITY = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SOURCE_INTERFACE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SOURCE_RECEIPT = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID DESTINATION_FACILITY = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID DESTINATION_INTERFACE = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID DESTINATION_RECEIPT = UUID.fromString("77777777-7777-7777-7777-777777777777");

    @TempDir
    Path temporaryDirectory;

    @Test
    void explicitExportToReceiptHandoffSurvivesReopen() {
        Path databaseFile = temporaryDirectory.resolve("handoff.sqlite3");
        UUID exportId;
        try (CivicDatabase database = database()) {
            ensureNation(database);
            registerInterface(database, SOURCE_FACILITY, SOURCE_INTERFACE, 1);
            registerInterface(database, DESTINATION_FACILITY, DESTINATION_INTERFACE, 20);
            recordReceipt(database, SOURCE_RECEIPT, SOURCE_INTERFACE, 1);
            recordReceipt(database, DESTINATION_RECEIPT, DESTINATION_INTERFACE, 20);
            assertEquals(DESTINATION_INTERFACE,
                    database.facilityAccountingInterfaceById(DESTINATION_INTERFACE).interfaceId());
            assertEquals(DESTINATION_RECEIPT,
                    database.facilityAccountingReceipt(DESTINATION_RECEIPT).receiptId());
            ProductionInventoryExport export = new ProductionInventoryExportCoordinator(
                    database,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    request -> new ProductionStack("create:wheat_flour", "components:{}", request.count()))
                    .export(new ProductionInventoryExportRequest(
                            SERVICE,
                            "handoff-export",
                            SOURCE_INTERFACE,
                            ACTOR,
                            0,
                            1,
                            ProductionInventoryExportKind.EXPORT,
                            "destination-facility-interface",
                            NOW.toEpochMilli(),
                            "Move output to destination facility"));
            exportId = export.exportId();

            ProductionInventoryExportHandoff handoff = new ProductionInventoryExportHandoffRegistry(
                    database, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC))
                    .record(new RecordProductionInventoryExportHandoff(
                            SERVICE,
                            "handoff-record",
                            exportId,
                            DESTINATION_INTERFACE,
                            DESTINATION_RECEIPT,
                            ACTOR,
                            "Record the explicit destination Receipt"));

            assertEquals(exportId, handoff.exportId());
            assertEquals(SOURCE_INTERFACE, handoff.sourceInterfaceId());
            assertEquals(DESTINATION_INTERFACE, handoff.destinationInterfaceId());
            assertEquals(DESTINATION_RECEIPT, handoff.destinationReceiptId());
        }

        try (CivicDatabase reopened = CivicDatabase.open(
                databaseFile,
                new DatabaseIdentity(
                        UUID.fromString("88888888-8888-8888-8888-888888888888"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"))) {
            ProductionInventoryExportHandoff restored =
                    new ProductionInventoryExportHandoffRegistry(
                            reopened, Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC))
                            .handoff("handoff-test", "handoff-record");
            assertEquals(exportId, restored.exportId());
            assertEquals(DESTINATION_RECEIPT, restored.destinationReceiptId());
        }
    }

    @Test
    void sameFacilityAndMissingDestinationAreRejectedBeforePersistence() {
        try (CivicDatabase database = database()) {
            ensureNation(database);
            registerInterface(database, SOURCE_FACILITY, SOURCE_INTERFACE, 1);
            recordReceipt(database, SOURCE_RECEIPT, SOURCE_INTERFACE, 1);
            ProductionInventoryExport export = new ProductionInventoryExportCoordinator(
                    database,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    request -> new ProductionStack("create:wheat_flour", "components:{}", request.count()))
                    .export(new ProductionInventoryExportRequest(
                            SERVICE,
                            "same-facility-export",
                            SOURCE_INTERFACE,
                            ACTOR,
                            0,
                            1,
                            ProductionInventoryExportKind.EXPORT,
                            "same-facility",
                            NOW.toEpochMilli(),
                            "Invalid local handoff"));

            ProductionInventoryExportHandoffRegistry registry =
                    new ProductionInventoryExportHandoffRegistry(
                            database, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
            assertThrows(
                    IllegalStateException.class,
                    () -> registry.record(new RecordProductionInventoryExportHandoff(
                            SERVICE,
                            "same-facility-record",
                            export.exportId(),
                            SOURCE_INTERFACE,
                            SOURCE_RECEIPT,
                            ACTOR,
                            "Must be rejected")));
            assertEquals(null, registry.handoff(SERVICE.value(), "same-facility-record"));
        }
    }

    @Test
    void changedReplayAndSecondDestinationForOneExportFailClosed() {
        try (CivicDatabase database = database()) {
            ensureNation(database);
            registerInterface(database, SOURCE_FACILITY, SOURCE_INTERFACE, 1);
            registerInterface(database, DESTINATION_FACILITY, DESTINATION_INTERFACE, 20);
            recordReceipt(database, SOURCE_RECEIPT, SOURCE_INTERFACE, 1);
            recordReceipt(database, DESTINATION_RECEIPT, DESTINATION_INTERFACE, 20);
            ProductionInventoryExport export = new ProductionInventoryExportCoordinator(
                    database,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    request -> new ProductionStack("create:wheat_flour", "components:{}", request.count()))
                    .export(new ProductionInventoryExportRequest(
                            SERVICE,
                            "replay-export",
                            SOURCE_INTERFACE,
                            ACTOR,
                            0,
                            1,
                            ProductionInventoryExportKind.EXPORT,
                            "destination-facility-interface",
                            NOW.toEpochMilli(),
                            "Move output to destination facility"));

            ProductionInventoryExportHandoffRegistry registry =
                    new ProductionInventoryExportHandoffRegistry(
                            database, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
            RecordProductionInventoryExportHandoff original =
                    new RecordProductionInventoryExportHandoff(
                            SERVICE,
                            "replay-handoff",
                            export.exportId(),
                            DESTINATION_INTERFACE,
                            DESTINATION_RECEIPT,
                            ACTOR,
                            "Original handoff");
            registry.record(original);

            assertThrows(
                    IllegalStateException.class,
                    () -> registry.record(new RecordProductionInventoryExportHandoff(
                            SERVICE,
                            "replay-handoff",
                            export.exportId(),
                            DESTINATION_INTERFACE,
                            DESTINATION_RECEIPT,
                            ACTOR,
                            "Changed reason")));
            assertThrows(
                    IllegalStateException.class,
                    () -> registry.record(new RecordProductionInventoryExportHandoff(
                            SERVICE,
                            "second-handoff",
                            export.exportId(),
                            DESTINATION_INTERFACE,
                            DESTINATION_RECEIPT,
                            ACTOR,
                            "Second destination claim")));
        }
    }

    @Test
    void saleExportCannotBeDeclaredAsACrossFacilityHandoff() {
        try (CivicDatabase database = database()) {
            ensureNation(database);
            registerInterface(database, SOURCE_FACILITY, SOURCE_INTERFACE, 1);
            registerInterface(database, DESTINATION_FACILITY, DESTINATION_INTERFACE, 20);
            recordReceipt(database, SOURCE_RECEIPT, SOURCE_INTERFACE, 1);
            recordReceipt(database, DESTINATION_RECEIPT, DESTINATION_INTERFACE, 20);
            ProductionInventoryExport sale = new ProductionInventoryExportCoordinator(
                    database,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    request -> new ProductionStack("create:wheat_flour", "components:{}", request.count()))
                    .export(new ProductionInventoryExportRequest(
                            SERVICE,
                            "sale-export",
                            SOURCE_INTERFACE,
                            ACTOR,
                            0,
                            1,
                            ProductionInventoryExportKind.SALE,
                            "external-market",
                            NOW.toEpochMilli(),
                            "Sale is not a transport handoff"));

            ProductionInventoryExportHandoffRegistry registry =
                    new ProductionInventoryExportHandoffRegistry(
                            database, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
            assertThrows(
                    IllegalStateException.class,
                    () -> registry.record(new RecordProductionInventoryExportHandoff(
                            SERVICE,
                            "sale-handoff",
                            sale.exportId(),
                            DESTINATION_INTERFACE,
                            DESTINATION_RECEIPT,
                            ACTOR,
                            "Must be rejected")));
        }
    }

    @Test
    void explicitDestinationReceiptWithDifferentGoodsFailsClosed() {
        try (CivicDatabase database = database()) {
            ensureNation(database);
            registerInterface(database, SOURCE_FACILITY, SOURCE_INTERFACE, 1);
            registerInterface(database, DESTINATION_FACILITY, DESTINATION_INTERFACE, 20);
            recordReceipt(database, SOURCE_RECEIPT, SOURCE_INTERFACE, 1);
            recordReceipt(
                    database,
                    DESTINATION_RECEIPT,
                    DESTINATION_INTERFACE,
                    20,
                    "minecraft:stone");
            ProductionInventoryExport export = new ProductionInventoryExportCoordinator(
                    database,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    request -> new ProductionStack("create:wheat_flour", "components:{}", request.count()))
                    .export(new ProductionInventoryExportRequest(
                            SERVICE,
                            "mismatched-goods-export",
                            SOURCE_INTERFACE,
                            ACTOR,
                            0,
                            1,
                            ProductionInventoryExportKind.EXPORT,
                            "destination-facility-interface",
                            NOW.toEpochMilli(),
                            "Move flour to destination facility"));

            ProductionInventoryExportHandoffRegistry registry =
                    new ProductionInventoryExportHandoffRegistry(
                            database, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
            assertThrows(
                    IllegalStateException.class,
                    () -> registry.record(new RecordProductionInventoryExportHandoff(
                            SERVICE,
                            "mismatched-goods-handoff",
                            export.exportId(),
                            DESTINATION_INTERFACE,
                            DESTINATION_RECEIPT,
                            ACTOR,
                            "Stone cannot prove a flour handoff")));
        }
    }

    @Test
    void destinationReceiptCannotPredateItsSourceExport() {
        try (CivicDatabase database = database()) {
            ensureNation(database);
            registerInterface(database, SOURCE_FACILITY, SOURCE_INTERFACE, 1);
            registerInterface(database, DESTINATION_FACILITY, DESTINATION_INTERFACE, 20);
            recordReceipt(database, SOURCE_RECEIPT, SOURCE_INTERFACE, 1);
            recordReceipt(
                    database,
                    DESTINATION_RECEIPT,
                    DESTINATION_INTERFACE,
                    20,
                    "create:wheat_flour",
                    NOW.minusSeconds(1));
            ProductionInventoryExport export = new ProductionInventoryExportCoordinator(
                    database,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    request -> new ProductionStack("create:wheat_flour", "components:{}", request.count()))
                    .export(new ProductionInventoryExportRequest(
                            SERVICE,
                            "future-export",
                            SOURCE_INTERFACE,
                            ACTOR,
                            0,
                            1,
                            ProductionInventoryExportKind.EXPORT,
                            "destination-facility-interface",
                            NOW.toEpochMilli(),
                            "Export after the alleged destination Receipt"));

            ProductionInventoryExportHandoffRegistry registry =
                    new ProductionInventoryExportHandoffRegistry(
                            database, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
            assertThrows(
                    IllegalStateException.class,
                    () -> registry.record(new RecordProductionInventoryExportHandoff(
                            SERVICE,
                            "past-receipt-handoff",
                            export.exportId(),
                            DESTINATION_INTERFACE,
                            DESTINATION_RECEIPT,
                            ACTOR,
                            "Causally impossible handoff")));
        }
    }

    private void recordReceipt(
            CivicDatabase database, UUID receiptId, UUID interfaceId, int x) {
        recordReceipt(database, receiptId, interfaceId, x, "create:wheat_flour");
    }

    private void recordReceipt(
            CivicDatabase database, UUID receiptId, UUID interfaceId, int x, String itemId) {
        recordReceipt(database, receiptId, interfaceId, x, itemId, NOW);
    }

    private void recordReceipt(
            CivicDatabase database,
            UUID receiptId,
            UUID interfaceId,
            int x,
            String itemId,
            Instant observedAt) {
        new ProductionInventoryAgeLedger(database, Clock.fixed(NOW, ZoneOffset.UTC)).recordReceipt(
                new FacilityAccountingReceipt(
                        receiptId,
                        interfaceId,
                        new FacilityAccountingInterfacePosition("minecraft:overworld", x, 70, 1),
                        observedAt.toEpochMilli(),
                        List.of(new MachineInventoryChange(
                                0,
                                new ProductionStack(itemId, "components:{}", 1)))));
    }

    private void registerInterface(
            CivicDatabase database, UUID facilityId, UUID interfaceId, int x) {
        new RegisteredFacilityRegistry(
                database,
                (nation, team, claim) -> true,
                Clock.fixed(NOW.minusSeconds(1), ZoneOffset.UTC),
                4)
                .register(new RegisterFacility(
                        SERVICE,
                        "facility-" + facilityId,
                        facilityId,
                        new org.civiceconomy.nation.NationId(
                                UUID.fromString("99999999-9999-9999-9999-999999999999")),
                        UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                        new FacilityCorePosition("minecraft:overworld", x, 70, 1),
                        List.of(new org.civiceconomy.territory.TerritoryClaimPosition(
                                "minecraft:overworld", x / 16, 0)),
                        ACTOR,
                        "Register handoff test facility"));
        new FacilityAccountingInterfaceRegistry(
                database, Clock.fixed(NOW.minusSeconds(1), ZoneOffset.UTC))
                .register(new RegisterFacilityAccountingInterface(
                        SERVICE,
                        "interface-" + interfaceId,
                        interfaceId,
                        facilityId,
                        new FacilityAccountingInterfacePosition("minecraft:overworld", x, 70, 1),
                        ACTOR,
                        "Register handoff test interface"));
    }

    private void ensureNation(CivicDatabase database) {
        database.registerNation(
                UUID.fromString("99999999-9999-9999-9999-999999999999"),
                SERVICE.value(),
                "Handoff nation",
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                NOW.minusSeconds(10).toEpochMilli());
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("handoff.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("88888888-8888-8888-8888-888888888888"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
