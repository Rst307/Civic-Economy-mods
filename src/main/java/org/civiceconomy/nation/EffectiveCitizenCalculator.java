package org.civiceconomy.nation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class EffectiveCitizenCalculator {
    private final CitizenshipRegistry citizenships;
    private final CitizenshipCorrectionGraceRegistry corrections;
    private final OnlineTimeLedger onlineTime;
    private final long observationWindowMillis;
    private final long fullContributionMillis;

    public EffectiveCitizenCalculator(
            CitizenshipRegistry citizenships,
            CitizenshipCorrectionGraceRegistry corrections,
            OnlineTimeLedger onlineTime,
            Duration observationWindow,
            Duration fullContributionTime) {
        if (citizenships == null || corrections == null || onlineTime == null
                || observationWindow == null || fullContributionTime == null) {
            throw new IllegalArgumentException("Effective Citizen calculator dependencies cannot be null");
        }
        if (observationWindow.isNegative() || observationWindow.isZero()
                || fullContributionTime.isNegative() || fullContributionTime.isZero()) {
            throw new IllegalArgumentException("Effective Citizen durations must be positive");
        }
        this.citizenships = citizenships;
        this.corrections = corrections;
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
        List<CitizenshipCorrectionGrace> correctionHistory = corrections.history(playerId);
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
            List<Suspension> suspensions = suspensions(
                    correctionHistory, citizenship.citizenshipId(), citizenshipStart, citizenshipEnd);
            for (OnlineInterval interval : onlineTime.history(playerId)) {
                long overlapStart = Math.max(citizenshipStart, interval.startedAtEpochMillis());
                long overlapEnd = Math.min(citizenshipEnd, interval.endedAtEpochMillis());
                if (overlapEnd > overlapStart) {
                    long credited = Math.subtractExact(overlapEnd, overlapStart);
                    for (Suspension suspension : suspensions) {
                        long excludedStart = Math.max(overlapStart, suspension.startedAtEpochMillis());
                        long excludedEnd = Math.min(overlapEnd, suspension.endedAtEpochMillis());
                        if (excludedEnd > excludedStart) {
                            credited = Math.subtractExact(
                                    credited, Math.subtractExact(excludedEnd, excludedStart));
                        }
                    }
                    attributed = Math.addExact(attributed, credited);
                }
            }
        }
        double contribution = Math.min(1D, (double) attributed / (double) fullContributionMillis);
        return new EffectiveCitizenContribution(playerId, nationId, attributed, contribution);
    }

    private static List<Suspension> suspensions(
            List<CitizenshipCorrectionGrace> history,
            UUID citizenshipId,
            long citizenshipStart,
            long citizenshipEnd) {
        List<Suspension> ordered = history.stream()
                .filter(grace -> grace.citizenshipId().equals(citizenshipId))
                .map(grace -> new Suspension(
                        Math.max(citizenshipStart, grace.startedAt().toEpochMilli()),
                        Math.min(citizenshipEnd, suspensionEnd(grace, citizenshipEnd))))
                .filter(suspension -> suspension.endedAtEpochMillis()
                        > suspension.startedAtEpochMillis())
                .sorted(Comparator.comparingLong(Suspension::startedAtEpochMillis))
                .toList();
        List<Suspension> merged = new ArrayList<>();
        for (Suspension next : ordered) {
            if (merged.isEmpty()) {
                merged.add(next);
                continue;
            }
            Suspension previous = merged.getLast();
            if (next.startedAtEpochMillis() > previous.endedAtEpochMillis()) {
                merged.add(next);
            } else {
                merged.set(
                        merged.size() - 1,
                        new Suspension(
                                previous.startedAtEpochMillis(),
                                Math.max(previous.endedAtEpochMillis(), next.endedAtEpochMillis())));
            }
        }
        return List.copyOf(merged);
    }

    private static long suspensionEnd(
            CitizenshipCorrectionGrace grace, long queryEndEpochMillis) {
        if (grace.resolution().isEmpty()) {
            return queryEndEpochMillis;
        }
        return switch (grace.resolution().orElseThrow()) {
            case RESTORED -> grace.resolvedAt().orElseThrow().toEpochMilli();
            case CITIZENSHIP_ENDED -> grace.deadline().toEpochMilli();
        };
    }

    private record Suspension(long startedAtEpochMillis, long endedAtEpochMillis) {}
}
