package org.civiceconomy.compat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class CompatibilityScanner {
    private final CompatibilityMatrix matrix;
    private final List<Probe> probes;

    private CompatibilityScanner(CompatibilityMatrix matrix, List<Probe> probes) {
        this.matrix = matrix;
        this.probes = List.copyOf(probes);
    }

    public static CompatibilityScanner firstSlice() {
        return new CompatibilityScanner(
                CompatibilityMatrix.firstSlice(),
                List.of(
                        new Probe("lightmanscurrency", CompatibilityMatrix.LC_PROBE_CLASSES),
                        new Probe("ftbteams", CompatibilityMatrix.FTB_TEAMS_PROBE_CLASSES),
                        new Probe("ftbchunks", CompatibilityMatrix.FTB_CHUNKS_PROBE_CLASSES),
                        new Probe("create", CompatibilityMatrix.CREATE_PROBE_CLASSES)));
    }

    public CompatibilityReport scan(ModCatalog catalog) {
        Map<String, DetectedMod> detectedMods = new HashMap<>();
        for (Probe probe : probes) {
            catalog.version(probe.modId()).ifPresent(version -> detectedMods.put(
                    probe.modId(),
                    new DetectedMod(version, availableClasses(catalog, probe.classNames()))));
        }
        return matrix.evaluate(detectedMods);
    }

    private static Set<String> availableClasses(ModCatalog catalog, Set<String> classNames) {
        return classNames.stream()
                .filter(catalog::classPresent)
                .collect(Collectors.toUnmodifiableSet());
    }

    private record Probe(String modId, Set<String> classNames) {
        private Probe {
            classNames = Set.copyOf(classNames);
        }
    }
}
