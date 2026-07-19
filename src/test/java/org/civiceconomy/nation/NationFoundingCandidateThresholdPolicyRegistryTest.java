package org.civiceconomy.nation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NationFoundingCandidateThresholdPolicyRegistryTest {
    private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-07-26T00:00:00Z");
    private static final ServiceIdentity SERVICE =
            new ServiceIdentity("civiceconomy-nation-founding-candidate-threshold-policy");

    @TempDir Path temporaryDirectory;

    @Test
    void scheduledThresholdChangesOnlyAtEffectiveTimeAndSurvivesRestart() {
        ScheduleNationFoundingCandidateThresholdPolicy request =
                new ScheduleNationFoundingCandidateThresholdPolicy(
                        SERVICE, "initial-founding-threshold", "civic-admin-console:test",
                        new NationFoundingCandidateThresholdPolicy(3), EFFECTIVE_AT,
                        "Initial formal founding threshold");
        NationFoundingCandidateThresholdPolicyVersion scheduled;
        try (CivicDatabase database = database()) {
            NationFoundingCandidateThresholdPolicyRegistry policies = registry(database);
            scheduled = policies.schedule(request);
            assertEquals(scheduled, policies.schedule(request));
            assertTrue(policies.current(EFFECTIVE_AT.minusMillis(1L)).isEmpty());
            assertEquals(3, policies.current(EFFECTIVE_AT).orElseThrow()
                    .policy().minimumEffectiveCandidates());
        }
        try (CivicDatabase reopened = database()) {
            assertEquals(scheduled, registry(reopened).current(EFFECTIVE_AT).orElseThrow());
        }
    }

    @Test
    void changedReplayCannotRewriteThresholdHistory() {
        try (CivicDatabase database = database()) {
            NationFoundingCandidateThresholdPolicyRegistry policies = registry(database);
            ScheduleNationFoundingCandidateThresholdPolicy original =
                    new ScheduleNationFoundingCandidateThresholdPolicy(
                            SERVICE, "immutable-founding-threshold", "civic-admin-console:test",
                            new NationFoundingCandidateThresholdPolicy(2), EFFECTIVE_AT,
                            "Original formal founding threshold");
            policies.schedule(original);
            assertThrows(org.civiceconomy.fiscal.IdempotencyConflictException.class,
                    () -> policies.schedule(new ScheduleNationFoundingCandidateThresholdPolicy(
                            SERVICE, original.requestId(), original.actorIdentity(),
                            new NationFoundingCandidateThresholdPolicy(4),
                            original.effectiveAt(), original.reason())));
        }
    }

    private NationFoundingCandidateThresholdPolicyRegistry registry(CivicDatabase database) {
        return new NationFoundingCandidateThresholdPolicyRegistry(
                database, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("nation-founding-threshold.sqlite3"),
                new DatabaseIdentity(UUID.fromString("6e86ce29-9ef8-4c49-af29-e4688e79e684"),
                        "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20"));
    }
}
