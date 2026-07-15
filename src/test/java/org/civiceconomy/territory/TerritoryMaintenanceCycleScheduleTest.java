package org.civiceconomy.territory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TerritoryMaintenanceCycleScheduleTest {
    @Test
    void policyEffectiveTimeAnchorsStableHalfOpenCycles() {
        UUID policyId = UUID.fromString("92b70f48-c468-465e-bc23-91abbbf21a83");
        Instant effectiveAt = Instant.parse("2026-09-01T00:00:00Z");
        TerritoryMaintenancePolicyVersion policy = policy(policyId, effectiveAt);

        assertTrue(new TerritoryMaintenanceCycleSchedule()
                .current(policy, effectiveAt.minusMillis(1))
                .isEmpty());
        TerritoryMaintenanceCycleWindow first = new TerritoryMaintenanceCycleSchedule()
                .current(policy, effectiveAt)
                .orElseThrow();
        TerritoryMaintenanceCycleWindow second = new TerritoryMaintenanceCycleSchedule()
                .current(policy, effectiveAt.plus(Duration.ofDays(9)))
                .orElseThrow();

        assertEquals(effectiveAt, first.startsAt());
        assertEquals(effectiveAt.plus(Duration.ofDays(7)), first.endsAt());
        assertEquals(effectiveAt.plus(Duration.ofDays(7)), second.startsAt());
        assertEquals(effectiveAt.plus(Duration.ofDays(14)), second.endsAt());
        assertEquals(
                "automatic-maintenance:" + policyId + ":" + second.startsAt().toEpochMilli(),
                second.requestId());
    }

    private static TerritoryMaintenancePolicyVersion policy(
            UUID policyId, Instant effectiveAt) {
        return new TerritoryMaintenancePolicyVersion(
                policyId,
                Duration.ofDays(7),
                100L,
                15_000,
                25L,
                30L,
                Duration.ofDays(14),
                6_000,
                effectiveAt,
                "console",
                "Test schedule",
                effectiveAt.minusSeconds(1));
    }
}
