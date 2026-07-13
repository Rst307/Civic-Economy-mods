package org.civiceconomy.compat;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.neoforged.fml.ModList;

public final class NeoForgeCompatibilityDetector {
    private static final Set<String> INTEGRATED_MODS = Set.of(
            "lightmanscurrency", "ftbteams", "ftbchunks", "create");
    private static final Set<String> PROBE_CLASSES = Stream.of(
                    CompatibilityMatrix.LC_PROBE_CLASSES,
                    CompatibilityMatrix.FTB_TEAMS_PROBE_CLASSES,
                    CompatibilityMatrix.FTB_CHUNKS_PROBE_CLASSES,
                    CompatibilityMatrix.CREATE_PROBE_CLASSES)
            .flatMap(Set::stream)
            .collect(Collectors.toUnmodifiableSet());

    public Map<String, DetectedMod> detect() {
        Map<String, DetectedMod> detectedMods = new HashMap<>();
        for (String modId : INTEGRATED_MODS) {
            ModList.get().getModContainerById(modId).ifPresent(container -> detectedMods.put(
                    modId,
                    new DetectedMod(
                            container.getModInfo().getVersion().toString(),
                            availableProbeClasses())));
        }
        return Map.copyOf(detectedMods);
    }

    private static Set<String> availableProbeClasses() {
        return PROBE_CLASSES.stream()
                .filter(NeoForgeCompatibilityDetector::isPresent)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean isPresent(String className) {
        try {
            Class.forName(className, false, NeoForgeCompatibilityDetector.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }
}
