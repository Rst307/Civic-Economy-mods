package org.civiceconomy.platform.neoforge;

import java.util.Locale;
import org.civiceconomy.strength.NationalStrengthComponent;
import org.civiceconomy.strength.NationalStrengthRecalculation;

final class NationalStrengthStatusFormatter {
    private NationalStrengthStatusFormatter() {}

    static String format(NationalStrengthRecalculation recalculation) {
        var window = recalculation.activityWindow();
        var population = recalculation.effectiveCitizenPopulation();
        return "Nation " + recalculation.nationId().value()
                + " strength=" + recalculation.assessment().totalBasisPoints()
                + " effectiveCitizenBasisPoints="
                + recalculation.assessment()
                        .component(NationalStrengthComponent.EFFECTIVE_CITIZENS)
                        .normalizedInputBasisPoints()
                + " effectiveCitizens=" + population.effectiveCitizenCount()
                + " populationEquivalent="
                + String.format(Locale.ROOT, "%.3f", population.populationEquivalent())
                + " activityBasisPoints=" + window.normalizedBasisPoints()
                + " acceptedValue=" + window.acceptedValueMinorUnits()
                + " accepted=" + window.acceptedCount()
                + " excluded=" + window.excludedCount()
                + " paused=" + recalculation.assessment().newMintAllocationPaused();
    }
}
