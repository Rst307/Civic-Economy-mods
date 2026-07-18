package org.civiceconomy.production;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ProductionInventoryAgeDecayTest {
    private static final Instant FIRST_OBSERVED = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void inventoryAgeHasSevenDayFullContributionAndThirtyDayExpiry() {
        ProductionInventoryAgeDecay decay = new ProductionInventoryAgeDecay(
                Duration.ofDays(7), Duration.ofDays(30));
        ProductionInventoryAge age = new ProductionInventoryAge(
                FIRST_OBSERVED.toEpochMilli());

        assertEquals(10_000, decay.basisPoints(
                age, FIRST_OBSERVED.plus(Duration.ofDays(7)).toEpochMilli()));
        assertEquals(0, decay.basisPoints(
                age, FIRST_OBSERVED.plus(Duration.ofDays(30)).toEpochMilli()));
        assertEquals(0, decay.basisPoints(
                age, FIRST_OBSERVED.plus(Duration.ofDays(45)).toEpochMilli()));
    }
}
