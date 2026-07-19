package org.civiceconomy.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductionMarginalReturnCalculatorTest {
    private static final UUID FACILITY_A =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID FACILITY_B =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final ProductionIndustryId MILLING =
            new ProductionIndustryId("create:milling");
    private static final ProductionIndustryId PRESSING =
            new ProductionIndustryId("create:pressing");

    @Test
    void valueAboveOneFacilitySoftCapReceivesLowerMarginalWeight() {
        ProductionMarginalReturnAssessment assessment =
                new ProductionMarginalReturnCalculator(new ProductionMarginalReturnPolicy(
                                100L,
                                5_000,
                                10_000L,
                                10_000))
                        .assess(List.of(
                                contribution("first", FACILITY_A, MILLING, 100L),
                                contribution("second", FACILITY_A, MILLING, 100L)));

        assertEquals(200L, assessment.rawValueMinorUnits());
        assertEquals(150L, assessment.afterFacilityReturnsMinorUnits());
        assertEquals(150L, assessment.finalValueMinorUnits());
        assertEquals(50L, assessment.facilityMarginalReductionMinorUnits());
        assertEquals(0L, assessment.industryMarginalReductionMinorUnits());
    }

    @Test
    void valueAcrossFacilitiesStillReceivesTheIndustryMarginalReturn() {
        ProductionMarginalReturnAssessment assessment =
                new ProductionMarginalReturnCalculator(new ProductionMarginalReturnPolicy(
                                10_000L,
                                10_000,
                                100L,
                                2_500))
                        .assess(List.of(
                                contribution("facility-a", FACILITY_A, MILLING, 100L),
                                contribution("facility-b", FACILITY_B, MILLING, 100L)));

        assertEquals(200L, assessment.rawValueMinorUnits());
        assertEquals(200L, assessment.afterFacilityReturnsMinorUnits());
        assertEquals(125L, assessment.finalValueMinorUnits());
        assertEquals(0L, assessment.facilityMarginalReductionMinorUnits());
        assertEquals(75L, assessment.industryMarginalReductionMinorUnits());
    }

    @Test
    void mixedBucketsPreserveEachLayerTargetExactly() {
        ProductionMarginalReturnCalculator calculator =
                new ProductionMarginalReturnCalculator(new ProductionMarginalReturnPolicy(
                        2L, 5_000, 1L, 5_000));
        ProductionMarginalReturnContribution aMilling =
                contribution("a-milling", FACILITY_A, MILLING, 2L);
        ProductionMarginalReturnContribution aPressing =
                contribution("a-pressing", FACILITY_A, PRESSING, 1L);
        ProductionMarginalReturnContribution bMilling =
                contribution("b-milling", FACILITY_B, MILLING, 1L);
        ProductionMarginalReturnAssessment assessment =
                calculator.assess(List.of(aMilling, aPressing, bMilling));

        assertEquals(4L, assessment.rawValueMinorUnits());
        assertEquals(3L, assessment.afterFacilityReturnsMinorUnits());
        assertEquals(2L, assessment.finalValueMinorUnits());
        assertEquals(
                new ProductionMarginalReturnBandAssessment(3L, 2L),
                assessment.facilityAssessments().get(FACILITY_A));
        assertEquals(
                new ProductionMarginalReturnBandAssessment(1L, 1L),
                assessment.facilityAssessments().get(FACILITY_B));
        assertEquals(
                new ProductionMarginalReturnBandAssessment(2L, 1L),
                assessment.industryAssessments().get(MILLING));
        assertEquals(
                new ProductionMarginalReturnBandAssessment(1L, 1L),
                assessment.industryAssessments().get(PRESSING));
        assertEquals(
                assessment,
                calculator.assess(List.of(bMilling, aPressing, aMilling)));
    }

    @Test
    void exactReplayCountsOnceAndChangedReplayFailsClosed() {
        ProductionMarginalReturnCalculator calculator =
                new ProductionMarginalReturnCalculator(new ProductionMarginalReturnPolicy(
                        100L, 5_000, 100L, 5_000));
        ProductionMarginalReturnContribution original =
                contribution("replay", FACILITY_A, MILLING, 10L);

        ProductionMarginalReturnAssessment replay =
                calculator.assess(List.of(original, original));

        assertEquals(10L, replay.rawValueMinorUnits());
        assertThrows(
                IllegalStateException.class,
                () -> calculator.assess(List.of(
                        original,
                        new ProductionMarginalReturnContribution(
                                original.observationId(),
                                FACILITY_A,
                                MILLING,
                                11L))));
    }

    @Test
    void aggregateOverflowFailsClosedInsteadOfWrappingTheScore() {
        ProductionMarginalReturnCalculator calculator =
                new ProductionMarginalReturnCalculator(new ProductionMarginalReturnPolicy(
                        Long.MAX_VALUE, 10_000, Long.MAX_VALUE, 10_000));

        assertThrows(
                IllegalStateException.class,
                () -> calculator.assess(List.of(
                        contribution("overflow-a", FACILITY_A, MILLING, Long.MAX_VALUE),
                        contribution("overflow-b", FACILITY_B, MILLING, 1L))));
    }

    private static ProductionMarginalReturnContribution contribution(
            String suffix,
            UUID facilityId,
            ProductionIndustryId industryId,
            long valueMinorUnits) {
        return new ProductionMarginalReturnContribution(
                UUID.nameUUIDFromBytes(
                        suffix.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                facilityId,
                industryId,
                valueMinorUnits);
    }
}
