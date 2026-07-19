package org.civiceconomy.compat;

import java.util.Set;

public record DetectedMod(String version, Set<String> availableClasses) {
    public DetectedMod {
        version = version == null ? "" : version;
        availableClasses = Set.copyOf(availableClasses);
    }
}
