package org.civiceconomy.nation;

import java.time.Duration;

public record NationApplicationExpiryPolicy(Duration scanInterval) {
    public NationApplicationExpiryPolicy {
        if (scanInterval == null || scanInterval.isZero() || scanInterval.isNegative()) {
            throw new IllegalArgumentException(
                    "Nation Application expiry scan interval must be positive");
        }
    }
}
