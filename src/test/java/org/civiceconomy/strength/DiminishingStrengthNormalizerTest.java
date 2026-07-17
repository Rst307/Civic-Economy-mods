package org.civiceconomy.strength;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DiminishingStrengthNormalizerTest {
    @Test
    void squareRootScaleRewardsGrowthWithDiminishingReturnsAndCapsAtFullStrength() {
        DiminishingStrengthNormalizer normalizer = new DiminishingStrengthNormalizer(4L);

        assertEquals(0, normalizer.normalize(0L));
        assertEquals(5_000, normalizer.normalize(1L));
        assertEquals(7_071, normalizer.normalize(2L));
        assertEquals(10_000, normalizer.normalize(4L));
        assertEquals(10_000, normalizer.normalize(16L));
    }
}
