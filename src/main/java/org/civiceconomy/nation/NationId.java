package org.civiceconomy.nation;

import java.util.UUID;

public record NationId(UUID value) {
    public NationId {
        if (value == null) {
            throw new IllegalArgumentException("NationId cannot be null");
        }
    }

    public static NationId create() {
        return new NationId(UUID.randomUUID());
    }
}
