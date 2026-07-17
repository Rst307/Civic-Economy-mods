package org.civiceconomy.strength;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import org.civiceconomy.nation.CitizenshipCorrectionGraceRegistry;
import org.civiceconomy.nation.CitizenshipRegistry;
import org.civiceconomy.nation.NationPopulationCalculator;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.nation.OnlineTimeLedger;
import org.civiceconomy.persistence.CivicDatabase;

public final class NationalStrengthSnapshotBuilder {
    private final CivicDatabase database;
    private final NationalStrengthRecalculator recalculator;
    private final Duration citizenshipTransferCooldown;
    private final Duration effectiveCitizenObservationWindow;
    private final Duration fullCitizenContributionTime;
    private final DiminishingStrengthNormalizer effectiveCitizenNormalizer;

    public NationalStrengthSnapshotBuilder(
            CivicDatabase database,
            Duration citizenshipTransferCooldown,
            Duration effectiveCitizenObservationWindow,
            Duration fullCitizenContributionTime,
            int effectiveCitizenFullStrengthScale,
            long activityWindowMillis,
            long activityFullStrengthScale) {
        if (database == null || citizenshipTransferCooldown == null
                || effectiveCitizenObservationWindow == null
                || fullCitizenContributionTime == null
                || citizenshipTransferCooldown.isNegative()
                || effectiveCitizenObservationWindow.isNegative()
                || effectiveCitizenObservationWindow.isZero()
                || fullCitizenContributionTime.isNegative()
                || fullCitizenContributionTime.isZero()) {
            throw new IllegalArgumentException("National Strength population settings are invalid");
        }
        this.database = database;
        this.citizenshipTransferCooldown = citizenshipTransferCooldown;
        this.effectiveCitizenObservationWindow = effectiveCitizenObservationWindow;
        this.fullCitizenContributionTime = fullCitizenContributionTime;
        this.effectiveCitizenNormalizer =
                new DiminishingStrengthNormalizer(effectiveCitizenFullStrengthScale);
        this.recalculator = new NationalStrengthRecalculator(
                database, activityWindowMillis, activityFullStrengthScale);
    }

    public NationalStrengthSnapshot recalculateAll(long recalculatedAtEpochMillis) {
        if (recalculatedAtEpochMillis <= 0L) {
            throw new IllegalArgumentException("National Strength snapshot time must be positive");
        }
        LinkedHashMap<NationId, NationalStrengthRecalculation> recalculations =
                new LinkedHashMap<>();
        Instant recalculatedAt = Instant.ofEpochMilli(recalculatedAtEpochMillis);
        Clock recalculationClock = Clock.fixed(recalculatedAt, ZoneOffset.UTC);
        NationPopulationCalculator populations = new NationPopulationCalculator(
                new CitizenshipRegistry(
                        database, citizenshipTransferCooldown, recalculationClock),
                new CitizenshipCorrectionGraceRegistry(database, recalculationClock),
                new OnlineTimeLedger(database),
                effectiveCitizenObservationWindow,
                fullCitizenContributionTime);
        for (var stored : database.registeredNations()) {
            NationId nationId = new NationId(stored.nationId());
            var population = populations.calculate(nationId, recalculatedAt);
            NationalStrengthComponents conservativeInputs = new NationalStrengthComponents(
                    effectiveCitizenNormalizer.normalize(population.populationEquivalent()),
                    0,
                    0,
                    0,
                    0,
                    EnumSet.of(
                            NationalStrengthComponent.PRODUCTION_AND_INFRASTRUCTURE,
                            NationalStrengthComponent.EFFECTIVE_TERRITORY,
                            NationalStrengthComponent.COMPLIANCE));
            recalculations.put(
                    nationId,
                    recalculator.recalculate(
                            nationId,
                            recalculatedAtEpochMillis,
                            population,
                            conservativeInputs));
        }
        return new NationalStrengthSnapshot(recalculatedAtEpochMillis, recalculations);
    }
}
