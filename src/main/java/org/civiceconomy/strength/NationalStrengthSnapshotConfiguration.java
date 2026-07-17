package org.civiceconomy.strength;

import java.time.Duration;

public record NationalStrengthSnapshotConfiguration(
        Duration citizenshipTransferCooldown,
        Duration effectiveCitizenObservationWindow,
        Duration fullCitizenContributionTime,
        int effectiveCitizenFullStrengthScale,
        int effectiveTerritoryFullStrengthScale,
        Duration complianceWindow,
        Duration activityWindow,
        long activityFullStrengthScale) {
    public NationalStrengthSnapshotConfiguration {
        if (citizenshipTransferCooldown == null
                || effectiveCitizenObservationWindow == null
                || fullCitizenContributionTime == null
                || complianceWindow == null || activityWindow == null
                || citizenshipTransferCooldown.isNegative()
                || effectiveCitizenObservationWindow.isNegative()
                || effectiveCitizenObservationWindow.isZero()
                || fullCitizenContributionTime.isNegative()
                || fullCitizenContributionTime.isZero()
                || complianceWindow.isNegative() || complianceWindow.isZero()
                || activityWindow.isNegative()
                || activityWindow.isZero()
                || effectiveCitizenFullStrengthScale <= 0
                || effectiveTerritoryFullStrengthScale <= 0
                || activityFullStrengthScale <= 0L) {
            throw new IllegalArgumentException("National Strength snapshot configuration is invalid");
        }
    }
}
