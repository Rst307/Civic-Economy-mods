package org.civiceconomy.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompatibilityMatrixTest {
    private final CompatibilityMatrix matrix = CompatibilityMatrix.firstSlice();

    @Test
    void exactRequiredVersionsAllowTheFiscalCoreToStart() {
        CompatibilityReport report = matrix.evaluate(supportedRequiredMods());

        assertTrue(report.startupAllowed());
        assertFalse(report.productionScoringEnabled());
        assertTrue(report.problems().isEmpty());
    }

    @Test
    void aMissingRequiredDependencyFailsClosed() {
        Map<String, DetectedMod> mods = supportedRequiredMods();
        mods.remove("ftbchunks");

        CompatibilityReport report = matrix.evaluate(mods);

        assertFalse(report.startupAllowed());
        assertTrue(report.problems().stream().anyMatch(problem -> problem.modId().equals("ftbchunks")));
    }

    @Test
    void anUnknownRequiredVersionFailsClosed() {
        Map<String, DetectedMod> mods = supportedRequiredMods();
        mods.put("lightmanscurrency", detected("1.21-2.3.0.4", CompatibilityMatrix.LC_PROBE_CLASSES));

        CompatibilityReport report = matrix.evaluate(mods);

        assertFalse(report.startupAllowed());
        assertTrue(report.problems().stream().anyMatch(problem -> problem.reason().contains("version")));
    }

    @Test
    void aMissingRequiredProbeClassFailsClosed() {
        Map<String, DetectedMod> mods = supportedRequiredMods();
        mods.put("ftbteams", detected("2101.1.10", Set.of()));

        CompatibilityReport report = matrix.evaluate(mods);

        assertFalse(report.startupAllowed());
        assertTrue(report.problems().stream().anyMatch(problem -> problem.reason().contains("class")));
    }

    @Test
    void aSupportedCreateVersionEnablesProductionScoring() {
        Map<String, DetectedMod> mods = supportedRequiredMods();
        mods.put("create", detected("6.0.6", CompatibilityMatrix.CREATE_PROBE_CLASSES));

        CompatibilityReport report = matrix.evaluate(mods);

        assertTrue(report.startupAllowed());
        assertTrue(report.productionScoringEnabled());
        assertTrue(report.problems().isEmpty());
    }

    @Test
    void anUnknownCreateVersionDisablesScoringWithoutDisablingTheFiscalCore() {
        Map<String, DetectedMod> mods = supportedRequiredMods();
        mods.put("create", detected("6.0.10", CompatibilityMatrix.CREATE_PROBE_CLASSES));

        CompatibilityReport report = matrix.evaluate(mods);

        assertTrue(report.startupAllowed());
        assertFalse(report.productionScoringEnabled());
        assertTrue(report.problems().stream().anyMatch(problem -> problem.modId().equals("create")));
    }

    private static Map<String, DetectedMod> supportedRequiredMods() {
        Map<String, DetectedMod> mods = new HashMap<>();
        mods.put("lightmanscurrency", detected("1.21-2.3.0.5", CompatibilityMatrix.LC_PROBE_CLASSES));
        mods.put("ftbteams", detected("2101.1.10", CompatibilityMatrix.FTB_TEAMS_PROBE_CLASSES));
        mods.put("ftbchunks", detected("2101.1.20", CompatibilityMatrix.FTB_CHUNKS_PROBE_CLASSES));
        return mods;
    }

    private static DetectedMod detected(String version, Set<String> availableClasses) {
        return new DetectedMod(version, availableClasses);
    }
}
