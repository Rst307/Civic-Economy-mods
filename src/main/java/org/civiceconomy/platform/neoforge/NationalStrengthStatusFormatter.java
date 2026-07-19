package org.civiceconomy.platform.neoforge;

import java.util.Locale;
import org.civiceconomy.strength.NationalStrengthComponent;
import org.civiceconomy.strength.NationalStrengthRecalculation;

final class NationalStrengthStatusFormatter {
    private NationalStrengthStatusFormatter() {}

    static String format(NationalStrengthRecalculation recalculation) {
        var window = recalculation.activityWindow();
        var population = recalculation.effectiveCitizenPopulation();
        var territory = recalculation.effectiveTerritory();
        var production = recalculation.productionMarginalReturn();
        var compliance = recalculation.mintCompliance();
        return "Nation " + recalculation.nationId().value()
                + " strength=" + recalculation.assessment().totalBasisPoints()
                + " effectiveCitizenBasisPoints="
                + recalculation.assessment()
                        .component(NationalStrengthComponent.EFFECTIVE_CITIZENS)
                        .normalizedInputBasisPoints()
                + " effectiveCitizens=" + population.effectiveCitizenCount()
                + " populationEquivalent="
                + String.format(Locale.ROOT, "%.3f", population.populationEquivalent())
                + " effectiveTerritoryBasisPoints="
                + recalculation.assessment()
                        .component(NationalStrengthComponent.EFFECTIVE_TERRITORY)
                        .normalizedInputBasisPoints()
                + " currentClaims=" + territory.currentClaimCount()
                + " effectiveClaims=" + territory.effectiveClaimCount()
                + " suspendedClaims=" + territory.suspendedClaimCount()
                + " unassessedClaims=" + territory.unassessedClaimCount()
                + " territoryAvailable=" + territory.ownershipAvailable()
                + " productionBasisPoints="
                + recalculation.assessment()
                        .component(NationalStrengthComponent.PRODUCTION_AND_INFRASTRUCTURE)
                        .normalizedInputBasisPoints()
                + " productionFinalValue=" + production.finalValueMinorUnits()
                + " productionAccepted=" + production.acceptedContributionCount()
                + " productionUnbound=" + production.unboundExportedObservationCount()
                + " complianceBasisPoints="
                + recalculation.assessment()
                        .component(NationalStrengthComponent.COMPLIANCE)
                        .normalizedInputBasisPoints()
                + " complianceObservations=" + compliance.observationCount()
                + " cleanCommits=" + compliance.cleanCommitCount()
                + " recoveredCommits=" + compliance.recoveredCommitCount()
                + " quarantinedRecoveries=" + compliance.quarantinedRecoveryCount()
                + " openIncidents=" + compliance.openIncidentCount()
                + " activityBasisPoints=" + window.normalizedBasisPoints()
                + " acceptedValue=" + window.acceptedValueMinorUnits()
                + " accepted=" + window.acceptedCount()
                + " excluded=" + window.excludedCount()
                + " paused=" + recalculation.assessment().newMintAllocationPaused();
    }
}
