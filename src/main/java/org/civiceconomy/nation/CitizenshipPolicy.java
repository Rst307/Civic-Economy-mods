package org.civiceconomy.nation;

import java.time.Duration;

public record CitizenshipPolicy(
        Duration correctionGrace,
        Duration transferCooldown,
        Duration reconciliationInterval) {
    public CitizenshipPolicy {
        if (correctionGrace == null || correctionGrace.isNegative() || correctionGrace.isZero()
                || transferCooldown == null || transferCooldown.isNegative()
                || reconciliationInterval == null
                || reconciliationInterval.isNegative()
                || reconciliationInterval.isZero()) {
            throw new IllegalArgumentException("Citizenship policy durations are invalid");
        }
    }
}
