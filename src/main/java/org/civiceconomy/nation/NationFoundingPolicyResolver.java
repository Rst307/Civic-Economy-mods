package org.civiceconomy.nation;

import java.time.Clock;
import java.time.Duration;
import org.civiceconomy.persistence.CivicDatabase;

public final class NationFoundingPolicyResolver {
    private final CivicDatabase database;
    private final Clock clock;

    public NationFoundingPolicyResolver(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException("Nation founding policy dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public NationFoundingPolicy current(boolean debugWorld) {
        Duration observationWindow = new CandidateOnlineEvidencePolicyRegistry(database, clock)
                .current(clock.instant())
                .orElseThrow(() -> new SecurityException(
                        "Candidate Online Evidence policy is not configured"))
                .policy()
                .observationWindow();
        int minimumEffectiveCandidates =
                new NationFoundingCandidateThresholdPolicyRegistry(database, clock)
                        .current(clock.instant())
                        .orElseThrow(() -> new SecurityException(
                                "Formal Nation founding candidate threshold policy is not configured"))
                        .policy()
                        .minimumEffectiveCandidates();
        Duration transferCooldown = new CitizenshipPolicyRegistry(database, clock)
                .current(clock.instant())
                .orElseThrow(() -> new SecurityException(
                        "Citizenship policy is not configured"))
                .policy()
                .transferCooldown();
        return debugWorld
                ? NationFoundingPolicy.debugWorld(
                        minimumEffectiveCandidates, observationWindow, transferCooldown)
                : NationFoundingPolicy.formal(
                        minimumEffectiveCandidates, observationWindow, transferCooldown);
    }
}
