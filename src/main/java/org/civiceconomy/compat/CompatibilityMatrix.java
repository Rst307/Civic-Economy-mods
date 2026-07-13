package org.civiceconomy.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CompatibilityMatrix {
    public static final Set<String> LC_PROBE_CLASSES = Set.of(
            "io.github.lightman314.lightmanscurrency.api.money.bank.BankAPI",
            "io.github.lightman314.lightmanscurrency.api.money.bank.IBankAccount");
    public static final Set<String> FTB_TEAMS_PROBE_CLASSES = Set.of(
            "dev.ftb.mods.ftbteams.api.FTBTeamsAPI");
    public static final Set<String> FTB_CHUNKS_PROBE_CLASSES = Set.of(
            "dev.ftb.mods.ftbchunks.api.FTBChunksAPI",
            "dev.ftb.mods.ftbchunks.api.ClaimedChunkManager");
    public static final Set<String> CREATE_PROBE_CLASSES = Set.of(
            "com.simibubi.create.content.processing.recipe.ProcessingRecipe");

    private final Map<String, SupportedMod> requiredMods;
    private final SupportedMod create;

    private CompatibilityMatrix(Map<String, SupportedMod> requiredMods, SupportedMod create) {
        this.requiredMods = Map.copyOf(requiredMods);
        this.create = create;
    }

    public static CompatibilityMatrix firstSlice() {
        return new CompatibilityMatrix(
                Map.of(
                        "lightmanscurrency", new SupportedMod("1.21-2.3.0.5", LC_PROBE_CLASSES),
                        "ftbteams", new SupportedMod("2101.1.10", FTB_TEAMS_PROBE_CLASSES),
                        "ftbchunks", new SupportedMod("2101.1.20", FTB_CHUNKS_PROBE_CLASSES)),
                new SupportedMod("6.0.6", CREATE_PROBE_CLASSES));
    }

    public CompatibilityReport evaluate(Map<String, DetectedMod> detectedMods) {
        List<CompatibilityProblem> problems = new ArrayList<>();
        for (Map.Entry<String, SupportedMod> entry : requiredMods.entrySet()) {
            validate(entry.getKey(), entry.getValue(), detectedMods.get(entry.getKey()), problems);
        }

        boolean startupAllowed = problems.isEmpty();
        boolean productionScoringEnabled = false;
        DetectedMod detectedCreate = detectedMods.get("create");
        if (detectedCreate != null) {
            int problemCount = problems.size();
            validate("create", create, detectedCreate, problems);
            productionScoringEnabled = problems.size() == problemCount;
        }

        return new CompatibilityReport(startupAllowed, productionScoringEnabled, problems);
    }

    private static void validate(
            String modId,
            SupportedMod supported,
            DetectedMod detected,
            List<CompatibilityProblem> problems) {
        if (detected == null) {
            problems.add(new CompatibilityProblem(modId, "required mod is missing"));
            return;
        }
        if (!supported.version().equals(detected.version())) {
            problems.add(new CompatibilityProblem(
                    modId,
                    "unsupported version " + detected.version() + "; expected " + supported.version()));
        }
        Set<String> missingClasses = new java.util.HashSet<>(supported.probeClasses());
        missingClasses.removeAll(detected.availableClasses());
        if (!missingClasses.isEmpty()) {
            problems.add(new CompatibilityProblem(modId, "missing probe class: " + missingClasses));
        }
    }

    private record SupportedMod(String version, Set<String> probeClasses) {
        private SupportedMod {
            probeClasses = Set.copyOf(probeClasses);
        }
    }
}
