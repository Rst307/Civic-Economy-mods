package org.civiceconomy.nation;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public final class EffectiveCitizenCalculator {
    private final CitizenshipRegistry citizenships;
    private final OnlineTimeLedger onlineTime;
    private final long observationWindowMillis;
    private final long fullContributionMillis;

    public EffectiveCitizenCalculator(
            CitizenshipRegistry citizenships,
            OnlineTimeLedger onlineTime,
            Duration observationWindow,
            Duration fullContributionTime) {
        if (citizenships == null || onlineTime == null || observationWindow == null || fullContributionTime == null) {
            throw new IllegalArgumentException("Effective Citizen calculator dependencies cannot be null");
        }
        if (observationWindow.isNegative() || observationWindow.isZero()
                || fullContributionTime.isNegative() || fullContributionTime.isZero()) {
            throw new IllegalArgumentException("Effective Citizen durations must be positive");
        }
        this.citizenships = citizenships;
        this.onlineTime = onlineTime;
        this.observationWindowMillis = observationWindow.toMillis();
        this.fullContributionMillis = fullContributionTime.toMillis();
    }

    public EffectiveCitizenContribution contribution(UUID playerId, NationId nationId, Instant asOf) {
        if (playerId == null || nationId == null || asOf == null) {
            throw new IllegalArgumentException("Effective Citizen query cannot contain null values");
        }
        long windowEnd = asOf.toEpochMilli();
        if (windowEnd < 0) {
            throw new IllegalArgumentException("Effective Citizen query time cannot be negative");
        }
        long windowStart = Math.max(0L, Math.subtractExact(windowEnd, observationWindowMillis));
        long attributed = 0L;
        for (Citizenship citizenship : citizenships.history(playerId)) {
            if (!citizenship.nationId().equals(nationId)) {
                continue;
            }
            long citizenshipStart = Math.max(windowStart, citizenship.joinedAtEpochMillis());
            long citizenshipEnd = Math.min(
                    windowEnd, citizenship.endedAtEpochMillis().orElse(windowEnd));
            if (citizenshipEnd <= citizenshipStart) {
                continue;
            }
            for (OnlineInterval interval : onlineTime.history(playerId)) {
                long overlapStart = Math.max(citizenshipStart, interval.startedAtEpochMillis());
                long overlapEnd = Math.min(citizenshipEnd, interval.endedAtEpochMillis());
                if (overlapEnd > overlapStart) {
                    attributed = Math.addExact(
                            attributed, Math.subtractExact(overlapEnd, overlapStart));
                }
            }
        }
        double contribution = Math.min(1D, (double) attributed / (double) fullContributionMillis);
        return new EffectiveCitizenContribution(playerId, nationId, attributed, contribution);
    }
}
