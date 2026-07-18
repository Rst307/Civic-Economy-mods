package org.civiceconomy.platform.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.civiceconomy.nation.EffectiveCitizenContribution;
import org.civiceconomy.nation.NationEffectiveCitizenPopulation;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.strength.AuditableEconomicActivityWindowAssessment;
import org.civiceconomy.strength.EffectiveTerritoryStrengthAssessment;
import org.civiceconomy.strength.NationalStrengthCalculator;
import org.civiceconomy.strength.NationalStrengthComponent;
import org.civiceconomy.strength.NationalStrengthComponents;
import org.civiceconomy.strength.NationalStrengthRecalculation;
import org.civiceconomy.strength.MintComplianceCalculator;
import org.junit.jupiter.api.Test;

class NationalStrengthStatusFormattingTest {
    @Test
    void explainsEffectiveCitizenAndAuditableActivityInputs() {
        NationId nationId = new NationId(
                UUID.fromString("11111111-2222-3333-4444-555555555555"));
        long recalculatedAt = 2_000L;
        NationEffectiveCitizenPopulation population = new NationEffectiveCitizenPopulation(
                nationId,
                Instant.ofEpochMilli(recalculatedAt),
                List.of(new EffectiveCitizenContribution(
                        UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                        nationId,
                        14_400_000L,
                        0.5D)));
        var assessment = new NationalStrengthCalculator().assess(new NationalStrengthComponents(
                5_000,
                0,
                2_500,
                0,
                0,
                EnumSet.of(
                        NationalStrengthComponent.PRODUCTION_AND_INFRASTRUCTURE,
                        NationalStrengthComponent.EFFECTIVE_TERRITORY,
                        NationalStrengthComponent.COMPLIANCE)));
        NationalStrengthRecalculation recalculation = new NationalStrengthRecalculation(
                nationId,
                recalculatedAt,
                assessment,
                population,
                new EffectiveTerritoryStrengthAssessment(
                        nationId,
                        UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                        false,
                        List.of()),
                new MintComplianceCalculator().assess(
                        nationId, 1_000L, recalculatedAt, List.of()),
                new AuditableEconomicActivityWindowAssessment(
                        nationId, 1_000L, recalculatedAt, 625L, 2_500, 2, 1, Map.of()));

        assertEquals(
                "Nation 11111111-2222-3333-4444-555555555555 strength=2250"
                        + " effectiveCitizenBasisPoints=5000 effectiveCitizens=1"
                        + " populationEquivalent=0.500 effectiveTerritoryBasisPoints=0"
                        + " currentClaims=0 effectiveClaims=0 suspendedClaims=0"
                        + " unassessedClaims=0 territoryAvailable=false productionBasisPoints=0"
                        + " productionFinalValue=0 productionAccepted=0 productionUnbound=0"
                        + " complianceBasisPoints=0"
                        + " complianceObservations=0 cleanCommits=0 recoveredCommits=0"
                        + " quarantinedRecoveries=0 openIncidents=0 activityBasisPoints=2500"
                        + " acceptedValue=625 accepted=2 excluded=1 paused=true",
                NationalStrengthStatusFormatter.format(recalculation));
    }
}
