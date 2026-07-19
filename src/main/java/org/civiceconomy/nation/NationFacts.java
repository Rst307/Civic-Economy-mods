package org.civiceconomy.nation;

import java.util.Set;
import java.util.UUID;

public record NationFacts(NationId nationId, UUID headId, Set<UUID> citizens) {
    public NationFacts {
        if (nationId == null || headId == null || citizens == null) {
            throw new IllegalArgumentException("Nation facts cannot contain null values");
        }
        citizens = Set.copyOf(citizens);
    }
}
