package org.civiceconomy.nation;

import java.time.Duration;

public record NationFoundingPolicy(
        int minimumEffectiveCandidates,
        Duration observationWindow,
        Duration citizenshipTransferCooldown,
        boolean minimumCandidateBypassAllowed) {
    public NationFoundingPolicy {
        if (minimumEffectiveCandidates < 1) {
            throw new IllegalArgumentException("Minimum Effective Candidate count must be positive");
        }
        if (observationWindow == null || observationWindow.isZero() || observationWindow.isNegative()) {
            throw new IllegalArgumentException("Founding observation window must be positive");
        }
        if (citizenshipTransferCooldown == null || citizenshipTransferCooldown.isNegative()) {
            throw new IllegalArgumentException("Citizenship transfer cooldown cannot be negative");
        }
    }

    public static NationFoundingPolicy formal(
            int minimumEffectiveCandidates,
            Duration observationWindow,
            Duration citizenshipTransferCooldown) {
        return new NationFoundingPolicy(
                minimumEffectiveCandidates,
                observationWindow,
                citizenshipTransferCooldown,
                false);
    }

    public static NationFoundingPolicy debugWorld(
            int minimumEffectiveCandidates,
            Duration observationWindow,
            Duration citizenshipTransferCooldown) {
        return new NationFoundingPolicy(
                minimumEffectiveCandidates,
                observationWindow,
                citizenshipTransferCooldown,
                true);
    }
}
