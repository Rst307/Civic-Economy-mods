package org.civiceconomy.nation;

import java.util.UUID;

public record RegisteredNation(NationId nationId, UUID ftbTeamId, long registeredAtEpochMillis) {
    public RegisteredNation {
        if (nationId == null || ftbTeamId == null) {
            throw new IllegalArgumentException("Registered Nation identity cannot contain null values");
        }
        if (registeredAtEpochMillis < 0) {
            throw new IllegalArgumentException("Nation registration time cannot be negative");
        }
    }
}
