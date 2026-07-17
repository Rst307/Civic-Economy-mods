package org.civiceconomy.strength;

import java.util.Map;
import org.civiceconomy.nation.NationId;

public record NationalStrengthSnapshot(
        long recalculatedAtEpochMillis,
        Map<NationId, NationalStrengthRecalculation> nations) {
    public NationalStrengthSnapshot {
        if (recalculatedAtEpochMillis <= 0L || nations == null
                || nations.entrySet().stream()
                        .anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)) {
            throw new IllegalArgumentException("National Strength snapshot is invalid");
        }
        nations = Map.copyOf(nations);
    }
}
