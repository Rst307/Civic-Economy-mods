package org.civiceconomy.nation;

import java.util.Set;
import java.util.UUID;

public record NationTeam(UUID teamId, UUID headId, Set<UUID> citizens) {
    public NationTeam {
        if (teamId == null || headId == null || citizens == null) {
            throw new IllegalArgumentException("Nation team facts cannot contain null values");
        }
        citizens = Set.copyOf(citizens);
    }
}
