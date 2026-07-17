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
                + " activityBasisPoints=" + window.normalizedBasisPoints()
                + " acceptedValue=" + window.acceptedValueMinorUnits()
                + " accepted=" + window.acceptedCount()
                + " excluded=" + window.excludedCount()
                + " paused=" + recalculation.assessment().newMintAllocationPaused();
    }
}
