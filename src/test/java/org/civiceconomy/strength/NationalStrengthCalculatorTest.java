package org.civiceconomy.strength;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class NationalStrengthCalculatorTest {
    @Test
    void anomalousProductionPausesOnlyThatComponentAndNewMintAllocation() {
        NationalStrengthAssessment assessment = new NationalStrengthCalculator().assess(
                new NationalStrengthComponents(
                        10_000,
                        8_000,
                        6_000,
                        4_000,
                        2_000,
                        EnumSet.of(NationalStrengthComponent.PRODUCTION_AND_INFRASTRUCTURE)));

        assertEquals(3_500, assessment.effectiveCitizenContribution());
        assertEquals(0, assessment.productionAndInfrastructureContribution());
        assertEquals(1_200, assessment.auditableEconomicActivityContribution());
        assertEquals(400, assessment.effectiveTerritoryContribution());
        assertEquals(200, assessment.complianceContribution());
        assertEquals(5_300, assessment.totalBasisPoints());
        assertEquals(
                NationalStrengthComponentState.PAUSED_ANOMALY,
                assessment.componentState(
                        NationalStrengthComponent.PRODUCTION_AND_INFRASTRUCTURE));
        assertEquals(
                NationalStrengthComponentState.ACTIVE,
                assessment.componentState(NationalStrengthComponent.EFFECTIVE_CITIZENS));
        assertTrue(assessment.newMintAllocationPaused());
    }

    @Test
    void componentBreakdownExplainsInputWeightStateAndContribution() {
        NationalStrengthAssessment assessment = new NationalStrengthCalculator().assess(
                new NationalStrengthComponents(
                        10_000,
                        8_000,
                        6_000,
                        4_000,
                        2_000,
                        EnumSet.of(NationalStrengthComponent.PRODUCTION_AND_INFRASTRUCTURE)));

        NationalStrengthComponentAssessment production = assessment.component(
                NationalStrengthComponent.PRODUCTION_AND_INFRASTRUCTURE);

        assertEquals(8_000, production.normalizedInputBasisPoints());
        assertEquals(25, production.weightPercent());
        assertEquals(0, production.weightedContributionBasisPoints());
        assertEquals(
                NationalStrengthComponentState.PAUSED_ANOMALY,
                production.state());
    }
}
