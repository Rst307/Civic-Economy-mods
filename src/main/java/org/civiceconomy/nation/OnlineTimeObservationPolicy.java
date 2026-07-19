package org.civiceconomy.nation;

import java.time.Duration;

public record OnlineTimeObservationPolicy(Duration checkpointInterval) {
    public OnlineTimeObservationPolicy {
        if (checkpointInterval == null
                || checkpointInterval.isZero()
                || checkpointInterval.isNegative()) {
            throw new IllegalArgumentException(
                    "Online Time Observation checkpoint interval must be positive");
        }
    }
}
