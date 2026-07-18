package org.civiceconomy.production;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductionValueAddedWindowTest {
    private static final Instant END = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant PRICE_EFFECTIVE_AT = END.minus(Duration.ofDays(31));
    private static final UUID FACILITY =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID INTERFACE =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID RECEIPT =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    @TempDir
    Path temporaryDirectory;

    @Test
    void usesHalfOpenThirtyDayWindowAndFullWeightForRecentSevenDays() {
        try (CivicDatabase database = database()) {
            GlobalReferencePriceRegistry prices = new GlobalReferencePriceRegistry(
                    database, Clock.fixed(PRICE_EFFECTIVE_AT, ZoneOffset.UTC));
            prices.schedule(new ScheduleGlobalReferencePrice(
                    new ServiceIdentity("window-price-test"),
                    "wheat-price",
                    "test-actor",
                    "minecraft:wheat",
                    "components:{}",
                    10L,
                    PRICE_EFFECTIVE_AT.plusMillis(1L),
                    "Window wheat price"));
            prices.schedule(new ScheduleGlobalReferencePrice(
                    new ServiceIdentity("window-price-test"),
                    "flour-price",
                    "test-actor",
                    "create:wheat_flour",
                    "components:{}",
                    25L,
                    PRICE_EFFECTIVE_AT.plusMillis(1L),
                    "Window flour price"));

            ProductionValueAddedWindowAssessment assessment =
                    new ProductionValueAddedWindow(
                                    new ProductionValueAddedCalculator(prices),
                                    Duration.ofDays(30),
                                    Duration.ofDays(7))
                            .assess(
                                    List.of(
                                            observation("recent", END.minus(Duration.ofDays(3))),
                                            observation("oldest", END.minus(Duration.ofDays(30))),
                                            observation("end", END)),
                                    END);

            assertEquals(2, assessment.acceptedObservationCount());
            assertEquals(30L, assessment.acceptedValueMinorUnits());
            assertEquals(15L, assessment.weightedValueMinorUnits());
            assertEquals(0, assessment.excludedObservationCount());
        }
    }

    @Test
    void repeatedImmutableObservationIsCountedOnlyOnce() {
        try (CivicDatabase database = database()) {
            GlobalReferencePriceRegistry prices = prices(database);
            FacilityProductionObservation recent =
                    observation("replay", END.minus(Duration.ofDays(3)));

            ProductionValueAddedWindowAssessment assessment =
                    window(prices).assess(List.of(recent, recent), END);

            assertEquals(1, assessment.acceptedObservationCount());
            assertEquals(15L, assessment.acceptedValueMinorUnits());
            assertEquals(15L, assessment.weightedValueMinorUnits());
        }
    }

    @Test
    void changedObservationReplayIsRejectedBeforeAggregation() {
        try (CivicDatabase database = database()) {
            GlobalReferencePriceRegistry prices = prices(database);
            FacilityProductionObservation original =
                    observation("conflict", END.minus(Duration.ofDays(3)));
            FacilityProductionObservation changed = new FacilityProductionObservation(
                    original.completion(),
                    original.receipt(),
                    new FacilityProductionDecision(
                            original.decision().observationId(),
                            original.decision().facilityId(),
                            original.decision().interfaceId(),
                            original.decision().receiptId(),
                            FacilityProductionDecisionKind.INCLUDED,
                            "Changed immutable reason"),
                    original.decidedAt());

            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () -> window(prices).assess(List.of(original, changed), END));
        }
    }

    @Test
    void olderThanSevenDaysReceivesAGraduallyReducedWeight() {
        try (CivicDatabase database = database()) {
            ProductionValueAddedWindowAssessment assessment =
                    window(prices(database)).assess(
                            List.of(observation("decay", END.minus(Duration.ofDays(10)))), END);

            assertEquals(15L, assessment.acceptedValueMinorUnits());
            assertEquals(13L, assessment.weightedValueMinorUnits());
        }
    }

    private GlobalReferencePriceRegistry prices(CivicDatabase database) {
        GlobalReferencePriceRegistry prices = new GlobalReferencePriceRegistry(
                database, Clock.fixed(PRICE_EFFECTIVE_AT, ZoneOffset.UTC));
        prices.schedule(new ScheduleGlobalReferencePrice(
                new ServiceIdentity("window-price-test"),
                "wheat-price",
                "test-actor",
                "minecraft:wheat",
                "components:{}",
                10L,
                PRICE_EFFECTIVE_AT.plusMillis(1L),
                "Window wheat price"));
        prices.schedule(new ScheduleGlobalReferencePrice(
                new ServiceIdentity("window-price-test"),
                "flour-price",
                "test-actor",
                "create:wheat_flour",
                "components:{}",
                25L,
                PRICE_EFFECTIVE_AT.plusMillis(1L),
                "Window flour price"));
        return prices;
    }

    private ProductionValueAddedWindow window(GlobalReferencePriceRegistry prices) {
        return new ProductionValueAddedWindow(
                new ProductionValueAddedCalculator(prices),
                Duration.ofDays(30),
                Duration.ofDays(7));
    }

    private FacilityProductionObservation observation(String suffix, Instant observedAt) {
        UUID observationId = UUID.nameUUIDFromBytes(
                ("production-window-" + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID receiptId = UUID.nameUUIDFromBytes(
                ("receipt-window-" + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        CreateRecipeCompletion completion = new CreateRecipeCompletion(
                observationId,
                "6.0.6",
                CreateMachineKind.MILLSTONE,
                "create:milling/wheat",
                "minecraft:overworld",
                2,
                70,
                2,
                observedAt.toEpochMilli(),
                new MachineInventoryDelta(
                        List.of(change(0, "minecraft:wheat", 1)),
                        List.of(change(0, "create:wheat_flour", 1))));
        FacilityAccountingReceipt receipt = new FacilityAccountingReceipt(
                receiptId,
                INTERFACE,
                new FacilityAccountingInterfacePosition(
                        "minecraft:overworld", 17, 72, 4),
                observedAt.toEpochMilli(),
                List.of(change(4, "create:wheat_flour", 1)));
        return new FacilityProductionObservation(
                completion,
                receipt,
                new FacilityProductionDecision(
                        observationId,
                        FACILITY,
                        INTERFACE,
                        receiptId,
                        FacilityProductionDecisionKind.INCLUDED,
                        "Accepted window fixture"),
                observedAt);
    }

    private static MachineInventoryChange change(int slot, String itemId, int count) {
        return new MachineInventoryChange(
                slot, new ProductionStack(itemId, "components:{}", count));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("production-value-window.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
