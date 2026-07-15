package org.civiceconomy.territory;

import java.time.Instant;
import java.util.Optional;

public final class TerritoryMaintenanceCycleSchedule {
    public Optional<TerritoryMaintenanceCycleWindow> current(
            TerritoryMaintenancePolicyVersion policy, Instant now) {
        if (policy == null || now == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Cycle Schedule cannot contain null values");
        }
        long effectiveAt = policy.effectiveAt().toEpochMilli();
        long current = now.toEpochMilli();
        if (current < effectiveAt) {
            return Optional.empty();
        }
        long duration = policy.cycleDuration().toMillis();
        long cycleIndex = Math.subtractExact(current, effectiveAt) / duration;
        long startsAt = Math.addExact(effectiveAt, Math.multiplyExact(cycleIndex, duration));
        long endsAt = Math.addExact(startsAt, duration);
        return Optional.of(new TerritoryMaintenanceCycleWindow(
                "automatic-maintenance:" + policy.policyId() + ":" + startsAt,
                Instant.ofEpochMilli(startsAt),
                Instant.ofEpochMilli(endsAt)));
    }
}
