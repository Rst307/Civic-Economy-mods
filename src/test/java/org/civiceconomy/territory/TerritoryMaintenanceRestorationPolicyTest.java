package org.civiceconomy.territory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.civiceconomy.fiscal.MoneyAmount;
import org.junit.jupiter.api.Test;

class TerritoryMaintenanceRestorationPolicyTest {
    private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");

    @Test
    void chargesOnlyConfiguredRestorationFeeAndNextFullCycleMaintenance() {
        var policy = new TerritoryMaintenanceRestorationPolicy(
                MoneyAmount.ofMinorUnits(25L), Duration.ofDays(7));

        var quote = policy.quote(
                MoneyAmount.ofMinorUnits(100L), Optional.empty(), NOW);

        assertEquals(MoneyAmount.ofMinorUnits(125L), quote.totalDue());
        assertEquals(NOW.plus(Duration.ofDays(7)), quote.cooldownEndsAt());
    }

    @Test
    void rejectsRestorationInsideCooldown() {
        var policy = new TerritoryMaintenanceRestorationPolicy(
                MoneyAmount.ofMinorUnits(25L), Duration.ofDays(7));

        assertThrows(
                TerritoryMaintenanceRestorationCooldownException.class,
                () -> policy.quote(
                        MoneyAmount.ofMinorUnits(100L),
                        Optional.of(NOW.minus(Duration.ofDays(3))),
                        NOW));
    }
}
