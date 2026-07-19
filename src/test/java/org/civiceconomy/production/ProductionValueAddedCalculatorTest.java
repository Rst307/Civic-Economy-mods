package org.civiceconomy.production;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

class ProductionValueAddedCalculatorTest {
    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-07-24T00:00:00Z");
    private static final UUID OBSERVATION =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID RECEIPT =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID FACILITY =
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID INTERFACE =
            UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @TempDir
    Path temporaryDirectory;

    @Test
    void calculatesAcceptedRecipeValueAddedFromExactGlobalPrices() {
        try (CivicDatabase database = database()) {
            GlobalReferencePriceRegistry prices = new GlobalReferencePriceRegistry(
                    database, Clock.fixed(NOW, ZoneOffset.UTC));
            prices.schedule(new ScheduleGlobalReferencePrice(
                    new ServiceIdentity("reference-price-test"),
                    "wheat-price",
                    "test-actor",
                    "minecraft:wheat",
                    "components:{}",
                    10L,
                    EFFECTIVE_AT,
                    "Test wheat price"));
            prices.schedule(new ScheduleGlobalReferencePrice(
                    new ServiceIdentity("reference-price-test"),
                    "flour-price",
                    "test-actor",
                    "create:wheat_flour",
                    "components:{}",
                    35L,
                    EFFECTIVE_AT,
                    "Test flour price"));

            ProductionValueAddedAssessment assessment =
                    new ProductionValueAddedCalculator(prices).calculate(
                            acceptedObservation());

            assertEquals(35L, assessment.outputReferenceValueMinorUnits());
            assertEquals(20L, assessment.inputReferenceValueMinorUnits());
            assertEquals(15L, assessment.valueAddedMinorUnits());
            assertEquals(ProductionValueAddedDecision.INCLUDED, assessment.decision());
        }
    }

    @Test
    void excludesAcceptedObservationWhenAnyExactStackIdentityIsUnpriced() {
        try (CivicDatabase database = database()) {
            GlobalReferencePriceRegistry prices = new GlobalReferencePriceRegistry(
                    database, Clock.fixed(NOW, ZoneOffset.UTC));
            prices.schedule(new ScheduleGlobalReferencePrice(
                    new ServiceIdentity("reference-price-test"),
                    "wheat-price",
                    "test-actor",
                    "minecraft:wheat",
                    "components:{}",
                    10L,
                    EFFECTIVE_AT,
                    "Test wheat price"));

            ProductionValueAddedAssessment assessment =
                    new ProductionValueAddedCalculator(prices).calculate(
                            acceptedObservation());

            assertEquals(ProductionValueAddedDecision.EXCLUDED_UNPRICED, assessment.decision());
            assertEquals(0L, assessment.valueAddedMinorUnits());
            assertEquals(0L, assessment.outputReferenceValueMinorUnits());
            assertEquals(0L, assessment.inputReferenceValueMinorUnits());
        }
    }

    @Test
    void excludesNonPositiveValueAddedButRetainsPricedAmounts() {
        try (CivicDatabase database = database()) {
            GlobalReferencePriceRegistry prices = new GlobalReferencePriceRegistry(
                    database, Clock.fixed(NOW, ZoneOffset.UTC));
            prices.schedule(new ScheduleGlobalReferencePrice(
                    new ServiceIdentity("reference-price-test"),
                    "wheat-price",
                    "test-actor",
                    "minecraft:wheat",
                    "components:{}",
                    10L,
                    EFFECTIVE_AT,
                    "Test wheat price"));
            prices.schedule(new ScheduleGlobalReferencePrice(
                    new ServiceIdentity("reference-price-test"),
                    "flour-price",
                    "test-actor",
                    "create:wheat_flour",
                    "components:{}",
                    20L,
                    EFFECTIVE_AT,
                    "Test flour price"));

            ProductionValueAddedAssessment assessment =
                    new ProductionValueAddedCalculator(prices).calculate(
                            acceptedObservation());

            assertEquals(ProductionValueAddedDecision.EXCLUDED_NON_POSITIVE,
                    assessment.decision());
            assertEquals(20L, assessment.outputReferenceValueMinorUnits());
            assertEquals(20L, assessment.inputReferenceValueMinorUnits());
            assertEquals(0L, assessment.valueAddedMinorUnits());
        }
    }

    @Test
    void laterReferencePriceCannotRevalueAnEarlierCompletion() {
        try (CivicDatabase database = database()) {
            GlobalReferencePriceRegistry prices = new GlobalReferencePriceRegistry(
                    database, Clock.fixed(NOW, ZoneOffset.UTC));
            prices.schedule(new ScheduleGlobalReferencePrice(
                    new ServiceIdentity("reference-price-test"),
                    "wheat-price",
                    "test-actor",
                    "minecraft:wheat",
                    "components:{}",
                    10L,
                    EFFECTIVE_AT,
                    "Initial wheat price"));
            prices.schedule(new ScheduleGlobalReferencePrice(
                    new ServiceIdentity("reference-price-test"),
                    "wheat-price-later",
                    "test-actor",
                    "minecraft:wheat",
                    "components:{}",
                    100L,
                    EFFECTIVE_AT.plusSeconds(1L),
                    "Later wheat price"));
            prices.schedule(new ScheduleGlobalReferencePrice(
                    new ServiceIdentity("reference-price-test"),
                    "flour-price",
                    "test-actor",
                    "create:wheat_flour",
                    "components:{}",
                    35L,
                    EFFECTIVE_AT,
                    "Initial flour price"));

            ProductionValueAddedAssessment assessment =
                    new ProductionValueAddedCalculator(prices).calculate(
                            acceptedObservation());

            assertEquals(15L, assessment.valueAddedMinorUnits());
        }
    }

    @Test
    void excludesAReceiptThatWasNotAcceptedByTheFacilityMatcher() {
        try (CivicDatabase database = database()) {
            GlobalReferencePriceRegistry prices = new GlobalReferencePriceRegistry(
                    database, Clock.fixed(NOW, ZoneOffset.UTC));
            prices.schedule(new ScheduleGlobalReferencePrice(
                    new ServiceIdentity("reference-price-test"),
                    "wheat-price",
                    "test-actor",
                    "minecraft:wheat",
                    "components:{}",
                    10L,
                    EFFECTIVE_AT,
                    "Test wheat price"));
            prices.schedule(new ScheduleGlobalReferencePrice(
                    new ServiceIdentity("reference-price-test"),
                    "flour-price",
                    "test-actor",
                    "create:wheat_flour",
                    "components:{}",
                    35L,
                    EFFECTIVE_AT,
                    "Test flour price"));
            FacilityProductionObservation accepted = acceptedObservation();
            FacilityProductionObservation rejected = new FacilityProductionObservation(
                    accepted.completion(),
                    accepted.receipt(),
                    new FacilityProductionDecision(
                            OBSERVATION,
                            FACILITY,
                            INTERFACE,
                            RECEIPT,
                            FacilityProductionDecisionKind.FACILITY_BASELINING,
                            "Facility baseline is not active"),
                    accepted.decidedAt());

            ProductionValueAddedAssessment assessment =
                    new ProductionValueAddedCalculator(prices).calculate(rejected);

            assertEquals(ProductionValueAddedDecision.EXCLUDED_NOT_INCLUDED,
                    assessment.decision());
            assertEquals(0L, assessment.valueAddedMinorUnits());
        }
    }

    private static FacilityProductionObservation acceptedObservation() {
        CreateRecipeCompletion completion = new CreateRecipeCompletion(
                OBSERVATION,
                "6.0.6",
                CreateMachineKind.MILLSTONE,
                "create:milling/wheat",
                "minecraft:overworld",
                2,
                70,
                2,
                EFFECTIVE_AT.toEpochMilli(),
                new MachineInventoryDelta(
                        List.of(change(0, "minecraft:wheat", 2)),
                        List.of(change(0, "create:wheat_flour", 1))));
        FacilityAccountingReceipt receipt = new FacilityAccountingReceipt(
                RECEIPT,
                INTERFACE,
                new FacilityAccountingInterfacePosition(
                        "minecraft:overworld", 17, 72, 4),
                EFFECTIVE_AT.toEpochMilli(),
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
                        "Accepted production evidence"),
                EFFECTIVE_AT);
    }

    private static MachineInventoryChange change(int slot, String itemId, int count) {
        return new MachineInventoryChange(
                slot, new ProductionStack(itemId, "components:{}", count));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("production-value-added.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
