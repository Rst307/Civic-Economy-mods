package org.civiceconomy.nation;

import java.util.UUID;

public record NationApplicationId(UUID value) {
    public NationApplicationId {
        if (value == null) {
            throw new IllegalArgumentException("Nation Application ID cannot be null");
        }
    }

    public static NationApplicationId create() {
        return new NationApplicationId(UUID.randomUUID());
    }
}
