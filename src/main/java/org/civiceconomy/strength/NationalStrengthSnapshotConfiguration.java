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
        long activityFullStrengthScale,
        Duration productionWindow,
        Duration productionFullWeightWindow,
        long productionFullStrengthScaleMinorUnits) {
    public NationalStrengthSnapshotConfiguration(
            Duration citizenshipTransferCooldown,
            Duration effectiveCitizenObservationWindow,
            Duration fullCitizenContributionTime,
            int effectiveCitizenFullStrengthScale,
            int effectiveTerritoryFullStrengthScale,
            Duration complianceWindow,
            Duration activityWindow,
            long activityFullStrengthScale) {
        this(
                citizenshipTransferCooldown,
                effectiveCitizenObservationWindow,
                fullCitizenContributionTime,
                effectiveCitizenFullStrengthScale,
                effectiveTerritoryFullStrengthScale,
                complianceWindow,
                activityWindow,
                activityFullStrengthScale,
                Duration.ofDays(30),
                Duration.ofDays(7),
                1L);
    }

    public NationalStrengthSnapshotConfiguration {
        if (citizenshipTransferCooldown == null
                || effectiveCitizenObservationWindow == null
                || fullCitizenContributionTime == null
                || complianceWindow == null || activityWindow == null
                || productionWindow == null || productionFullWeightWindow == null
                || citizenshipTransferCooldown.isNegative()
                || effectiveCitizenObservationWindow.isNegative()
                || effectiveCitizenObservationWindow.isZero()
                || fullCitizenContributionTime.isNegative()
                || fullCitizenContributionTime.isZero()
                || complianceWindow.isNegative() || complianceWindow.isZero()
                || activityWindow.isNegative()
                || activityWindow.isZero()
                || productionWindow.isNegative() || productionWindow.isZero()
                || productionFullWeightWindow.isNegative()
                || productionFullWeightWindow.isZero()
                || productionFullWeightWindow.compareTo(productionWindow) > 0
                || effectiveCitizenFullStrengthScale <= 0
                || effectiveTerritoryFullStrengthScale <= 0
                || activityFullStrengthScale <= 0L
                || productionFullStrengthScaleMinorUnits <= 0L) {
            throw new IllegalArgumentException("National Strength snapshot configuration is invalid");
        }
    }
}
