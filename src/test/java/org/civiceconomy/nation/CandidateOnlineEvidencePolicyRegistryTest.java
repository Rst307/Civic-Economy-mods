package org.civiceconomy.nation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CandidateOnlineEvidencePolicyRegistryTest {
    private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-07-26T00:00:00Z");
    private static final ServiceIdentity SERVICE =
            new ServiceIdentity("civiceconomy-candidate-online-evidence-policy");

    @TempDir Path temporaryDirectory;

    @Test
    void scheduledObservationWindowChangesOnlyAtEffectiveTimeAndSurvivesRestart() {
        ScheduleCandidateOnlineEvidencePolicy request =
                new ScheduleCandidateOnlineEvidencePolicy(
                        SERVICE,
                        "initial-candidate-evidence-policy",
                        "civic-admin-console:test",
                        new CandidateOnlineEvidencePolicy(Duration.ofDays(45)),
                        EFFECTIVE_AT,
                        "Initial Candidate Online Evidence policy");
        CandidateOnlineEvidencePolicyVersion scheduled;

        try (CivicDatabase database = database()) {
            CandidateOnlineEvidencePolicyRegistry policies = registry(database);
            scheduled = policies.schedule(request);

            assertEquals(scheduled, policies.schedule(request));
            assertTrue(policies.current(EFFECTIVE_AT.minusMillis(1L)).isEmpty());
            assertEquals(
                    Duration.ofDays(45),
                    policies.current(EFFECTIVE_AT).orElseThrow().policy().observationWindow());
        }

        try (CivicDatabase reopened = database()) {
            assertEquals(scheduled, registry(reopened).current(EFFECTIVE_AT).orElseThrow());
        }
    }

    @Test
    void changedRequestReplayCannotRewriteObservationWindowHistory() {
        try (CivicDatabase database = database()) {
            CandidateOnlineEvidencePolicyRegistry policies = registry(database);
            ScheduleCandidateOnlineEvidencePolicy original =
                    new ScheduleCandidateOnlineEvidencePolicy(
                            SERVICE,
                            "immutable-candidate-evidence-policy",
                            "civic-admin-console:test",
                            new CandidateOnlineEvidencePolicy(Duration.ofDays(45)),
                            EFFECTIVE_AT,
                            "Original Candidate Online Evidence policy");
            policies.schedule(original);

            assertThrows(
                    org.civiceconomy.fiscal.IdempotencyConflictException.class,
                    () -> policies.schedule(new ScheduleCandidateOnlineEvidencePolicy(
                            SERVICE,
                            original.requestId(),
                            original.actorIdentity(),
                            new CandidateOnlineEvidencePolicy(Duration.ofDays(30)),
                            original.effectiveAt(),
                            original.reason())));
        }
    }

    private CandidateOnlineEvidencePolicyRegistry registry(CivicDatabase database) {
        return new CandidateOnlineEvidencePolicyRegistry(
                database, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("candidate-online-evidence-policy.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("f93f676c-d407-4ab0-b295-df87ee6cb09d"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
