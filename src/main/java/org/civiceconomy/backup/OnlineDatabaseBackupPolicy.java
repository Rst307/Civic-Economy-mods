package org.civiceconomy.backup;

import java.time.Duration;

public record OnlineDatabaseBackupPolicy(Duration interval, int retention) {
    public OnlineDatabaseBackupPolicy {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("Online database backup interval must be positive");
        }
        if (retention < 1) {
            throw new IllegalArgumentException("Online database backup retention must be positive");
        }
    }
}
