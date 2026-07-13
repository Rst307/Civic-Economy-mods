package org.civiceconomy.nation;

import java.util.OptionalLong;
import java.util.UUID;

public record Citizenship(
        UUID citizenshipId,
        UUID playerId,
        NationId nationId,
        long joinedAtEpochMillis,
        OptionalLong endedAtEpochMillis) {
    public Citizenship {
        if (citizenshipId == null || playerId == null || nationId == null || endedAtEpochMillis == null) {
            throw new IllegalArgumentException("Citizenship cannot contain null values");
        }
        if (joinedAtEpochMillis < 0) {
            throw new IllegalArgumentException("Citizenship join time cannot be negative");
        }
        if (endedAtEpochMillis.isPresent() && endedAtEpochMillis.getAsLong() < joinedAtEpochMillis) {
            throw new IllegalArgumentException("Citizenship cannot end before it begins");
        }
    }

    public boolean active() {
        return endedAtEpochMillis.isEmpty();
    }
}
