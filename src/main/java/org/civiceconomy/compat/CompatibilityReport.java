package org.civiceconomy.compat;

import java.util.List;

public record CompatibilityReport(
        boolean startupAllowed,
        boolean productionScoringEnabled,
        List<CompatibilityProblem> problems) {
    public CompatibilityReport {
        problems = List.copyOf(problems);
    }
}
