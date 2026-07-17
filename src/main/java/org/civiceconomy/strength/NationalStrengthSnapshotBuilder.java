package org.civiceconomy.strength;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;

public final class NationalStrengthSnapshotBuilder {
    private final CivicDatabase database;
    private final NationalStrengthRecalculator recalculator;

    public NationalStrengthSnapshotBuilder(
            CivicDatabase database, long activityWindowMillis, long activityFullStrengthScale) {
        if (database == null) {
            throw new IllegalArgumentException("National Strength database is required");
        }
        this.database = database;
        this.recalculator = new NationalStrengthRecalculator(
                database, activityWindowMillis, activityFullStrengthScale);
    }

    public NationalStrengthSnapshot recalculateAll(long recalculatedAtEpochMillis) {
        if (recalculatedAtEpochMillis <= 0L) {
            throw new IllegalArgumentException("National Strength snapshot time must be positive");
        }
        LinkedHashMap<NationId, NationalStrengthRecalculation> recalculations =
                new LinkedHashMap<>();
        NationalStrengthComponents conservativeInputs = new NationalStrengthComponents(
                0,
                0,
                0,
                0,
                0,
                EnumSet.of(
                        NationalStrengthComponent.EFFECTIVE_CITIZENS,
                        NationalStrengthComponent.PRODUCTION_AND_INFRASTRUCTURE,
                        NationalStrengthComponent.EFFECTIVE_TERRITORY,
                        NationalStrengthComponent.COMPLIANCE));
        for (var stored : database.registeredNations()) {
            NationId nationId = new NationId(stored.nationId());
            recalculations.put(
                    nationId,
                    recalculator.recalculate(
                            nationId, recalculatedAtEpochMillis, conservativeInputs));
        }
        return new NationalStrengthSnapshot(recalculatedAtEpochMillis, recalculations);
    }
}
