package org.civiceconomy.strength;

import java.time.Duration;

public record NationalStrengthSnapshotConfiguration(
        Duration citizenshipTransferCooldown,
        Duration effectiveCitizenObservationWindow,
        Duration fullCitizenContributionTime) {
    public NationalStrengthSnapshotConfiguration {
        if (citizenshipTransferCooldown == null
                || effectiveCitizenObservationWindow == null
                || fullCitizenContributionTime == null
                || citizenshipTransferCooldown.isNegative()
                || effectiveCitizenObservationWindow.isNegative()
                || effectiveCitizenObservationWindow.isZero()
                || fullCitizenContributionTime.isNegative()
                || fullCitizenContributionTime.isZero()) {
            throw new IllegalArgumentException("National Strength snapshot configuration is invalid");
        }
    }
}
