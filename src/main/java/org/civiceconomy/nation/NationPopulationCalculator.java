package org.civiceconomy.nation;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;

public final class NationPopulationCalculator {
    private final CitizenshipRegistry citizenships;
    private final EffectiveCitizenCalculator citizens;

    public NationPopulationCalculator(
            CitizenshipRegistry citizenships,
            CitizenshipCorrectionGraceRegistry corrections,
            OnlineTimeLedger onlineTime,
            Duration observationWindow,
            Duration fullContributionTime) {
        if (citizenships == null) {
            throw new IllegalArgumentException("Nation population Citizenship registry cannot be null");
        }
        this.citizenships = citizenships;
        this.citizens = new EffectiveCitizenCalculator(
                citizenships,
                corrections,
                onlineTime,
                observationWindow,
                fullContributionTime);
    }

    public NationEffectiveCitizenPopulation calculate(NationId nationId, Instant asOf) {
        if (nationId == null || asOf == null) {
            throw new IllegalArgumentException("Nation population query cannot contain null values");
        }
        return new NationEffectiveCitizenPopulation(
                nationId,
                asOf,
                citizenships.playersWithHistory(nationId).stream()
                        .map(playerId -> citizens.contribution(playerId, nationId, asOf))
                        .sorted(Comparator.comparing(EffectiveCitizenContribution::playerId))
                        .toList());
    }
}
