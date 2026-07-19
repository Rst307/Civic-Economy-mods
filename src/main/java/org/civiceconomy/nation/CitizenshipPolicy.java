package org.civiceconomy.nation;

import java.time.Duration;

public record CitizenshipPolicy(Duration correctionGrace, Duration transferCooldown) {
    public CitizenshipPolicy {
        if (correctionGrace == null || correctionGrace.isNegative() || correctionGrace.isZero()
                || transferCooldown == null || transferCooldown.isNegative()) {
            throw new IllegalArgumentException("Citizenship policy durations are invalid");
        }
    }
}
