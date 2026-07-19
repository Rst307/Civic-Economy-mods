package org.civiceconomy.nation;

import java.time.Duration;

public record NationApplicationLifetimePolicy(Duration lifetime) {
    public NationApplicationLifetimePolicy {
        if (lifetime == null || lifetime.isZero() || lifetime.isNegative()) {
            throw new IllegalArgumentException("Nation Application lifetime must be positive");
        }
    }
}
