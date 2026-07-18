package org.civiceconomy.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.nation.NationId;
import org.junit.jupiter.api.Test;

class RollingProductionMarginalReturnCalculatorTest {
    private static final Instant END = Instant.parse("2026-10-01T00:00:00Z");
    private static final NationId NATION = new NationId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final UUID FACILITY =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final ProductionIndustryId INDUSTRY =
            new ProductionIndustryId("food-processing");

    @Test
    void policyVersionChangeDoesNotResetFacilitySoftCapUsage() {
        RollingProductionMarginalReturnAssessment assessment =
                new RollingProductionMarginalReturnCalculator(
                                Duration.ofDays(30), Duration.ofDays(7))
                        .assess(
                                NATION,
                                List.of(
                                        binding("old", END.minus(Duration.ofDays(2)), "old-policy"),
                                        binding("new", END.minus(Duration.ofDays(1)), "new-policy")),
                                END);

        assertEquals(200L, assessment.rawValueMinorUnits());
        assertEquals(200L, assessment.recencyWeightedValueMinorUnits());
        assertEquals(150L, assessment.afterFacilityReturnsMinorUnits());
        assertEquals(150L, assessment.finalValueMinorUnits());
        assertEquals(50L, assessment.facilityMarginalReductionMinorUnits());
        assertEquals(0L, assessment.industryMarginalReductionMinorUnits());
        assertEquals(2, assessment.acceptedContributionCount());
        assertEquals(2, assessment.contributions().size());
        assertEquals(0L, assessment.contributions().get(0).facilityUsageBeforeMinorUnits());
        assertEquals(100L, assessment.contributions().get(0).finalValueMinorUnits());
        assertEquals(100L, assessment.contributions().get(1).facilityUsageBeforeMinorUnits());
        assertEquals(50L, assessment.contributions().get(1).afterFacilityReturnsMinorUnits());
        assertEquals(50L, assessment.contributions().get(1).finalValueMinorUnits());
        assertEquals(
                UUID.nameUUIDFromBytes(
                        "new-policy".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                assessment.contributions().get(1).marginalReturnPolicyId());
    }

    @Test
    void exactBindingReplayCountsOnceAndChangedReplayFailsClosed() {
        BoundProductionMarginalReturnContribution original =
                binding("replay", END.minus(Duration.ofDays(1)), "replay-policy");
        RollingProductionMarginalReturnCalculator calculator =
                new RollingProductionMarginalReturnCalculator(
                        Duration.ofDays(30), Duration.ofDays(7));

        RollingProductionMarginalReturnAssessment replay =
                calculator.assess(NATION, List.of(original, original), END);

        assertEquals(1, replay.acceptedContributionCount());
        assertEquals(100L, replay.finalValueMinorUnits());
        assertThrows(
                IllegalStateException.class,
                () -> calculator.assess(
                        NATION,
                        List.of(
                                original,
                                new BoundProductionMarginalReturnContribution(
                                        original.anchorExportId(),
                                        new ProductionMarginalReturnContribution(
                                                original.contribution().observationId(),
                                                FACILITY,
                                                INDUSTRY,
                                                101L),
                                        original.industryAssignment(),
                                        original.marginalReturnPolicy(),
                                        original.evidenceAt(),
                                        original.recordedAt())),
                        END));
    }

    @Test
    void policyVersionChangeDoesNotResetIndustrySoftCapUsageAcrossFacilities() {
        UUID secondFacility =
                UUID.fromString("33333333-3333-3333-3333-333333333333");
        ProductionMarginalReturnPolicy policy =
                new ProductionMarginalReturnPolicy(10_000L, 10_000, 100L, 2_500);

        RollingProductionMarginalReturnAssessment assessment =
                new RollingProductionMarginalReturnCalculator(
                                Duration.ofDays(30), Duration.ofDays(7))
                        .assess(
                                NATION,
                                List.of(
                                        binding(
                                                "industry-old",
                                                END.minus(Duration.ofDays(2)),
                                                "industry-old-policy",
                                                FACILITY,
                                                policy),
                                        binding(
                                                "industry-new",
                                                END.minus(Duration.ofDays(1)),
                                                "industry-new-policy",
                                                secondFacility,
                                                policy)),
                                END);

        assertEquals(200L, assessment.afterFacilityReturnsMinorUnits());
        assertEquals(125L, assessment.finalValueMinorUnits());
        assertEquals(75L, assessment.industryMarginalReductionMinorUnits());
        assertEquals(100L, assessment.contributions().get(1).industryUsageBeforeMinorUnits());
        assertEquals(25L, assessment.contributions().get(1).finalValueMinorUnits());
    }

    @Test
    void usesHalfOpenThirtyDayWindowAndDecaysAfterSevenDays() {
        RollingProductionMarginalReturnCalculator calculator =
                new RollingProductionMarginalReturnCalculator(
                        Duration.ofDays(30), Duration.ofDays(7));

        RollingProductionMarginalReturnAssessment decayed = calculator.assess(
                NATION,
                List.of(binding(
                        "decayed", END.minus(Duration.ofDays(10)), "decayed-policy")),
                END);
        RollingProductionMarginalReturnAssessment boundaries = calculator.assess(
                NATION,
                List.of(
                        binding("start", END.minus(Duration.ofDays(30)), "start-policy"),
                        binding("end", END, "end-policy")),
                END);

        assertEquals(100L, decayed.rawValueMinorUnits());
        assertEquals(86L, decayed.recencyWeightedValueMinorUnits());
        assertEquals(8_695, decayed.contributions().get(0).recencyWeightBasisPoints());
        assertEquals(1, boundaries.acceptedContributionCount());
        assertEquals(100L, boundaries.rawValueMinorUnits());
        assertEquals(0L, boundaries.recencyWeightedValueMinorUnits());
        assertEquals(0, boundaries.contributions().get(0).recencyWeightBasisPoints());
    }

    private static BoundProductionMarginalReturnContribution binding(
            String suffix, Instant evidenceAt, String policySuffix) {
        return binding(
                suffix,
                evidenceAt,
                policySuffix,
                FACILITY,
                new ProductionMarginalReturnPolicy(100L, 5_000, 10_000L, 10_000));
    }

    private static BoundProductionMarginalReturnContribution binding(
            String suffix,
            Instant evidenceAt,
            String policySuffix,
            UUID facilityId,
            ProductionMarginalReturnPolicy policy) {
        UUID observationId = UUID.nameUUIDFromBytes(
                ("rolling-observation-" + suffix)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Instant effectiveAt = evidenceAt.minusSeconds(1L);
        return new BoundProductionMarginalReturnContribution(
                UUID.nameUUIDFromBytes(
                        ("rolling-export-" + suffix)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                new ProductionMarginalReturnContribution(
                        observationId, facilityId, INDUSTRY, 100L),
                new ProductionIndustryAssignmentVersion(
                        UUID.nameUUIDFromBytes(
                                ("rolling-industry-" + suffix)
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        "6.0.6",
                        "create:milling/wheat",
                        INDUSTRY,
                        effectiveAt,
                        "test-admin",
                        "Trusted industry",
                        effectiveAt.minusSeconds(1L)),
                new ProductionMarginalReturnPolicyVersion(
                        UUID.nameUUIDFromBytes(
                                policySuffix.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        policy,
                        effectiveAt,
                        "test-admin",
                        "Trusted marginal policy",
                        effectiveAt.minusSeconds(1L)),
                evidenceAt,
                evidenceAt.plusSeconds(1L));
    }
}
