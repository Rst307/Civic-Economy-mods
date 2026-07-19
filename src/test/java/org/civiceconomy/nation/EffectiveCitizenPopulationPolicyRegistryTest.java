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

class EffectiveCitizenPopulationPolicyRegistryTest {
    private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-07-26T00:00:00Z");
    private static final ServiceIdentity SERVICE =
            new ServiceIdentity("civiceconomy-effective-citizen-population-policy");

    @TempDir Path temporaryDirectory;

    @Test
    void scheduledPopulationInterpretationChangesOnlyAtEffectiveTimeAndSurvivesRestart() {
        ScheduleEffectiveCitizenPopulationPolicy request =
                new ScheduleEffectiveCitizenPopulationPolicy(
                        SERVICE, "initial-population-policy", "civic-admin-console:test",
                        new EffectiveCitizenPopulationPolicy(
                                Duration.ofDays(45), Duration.ofHours(6)),
                        EFFECTIVE_AT, "Initial Effective Citizen population policy");
        EffectiveCitizenPopulationPolicyVersion scheduled;
        try (CivicDatabase database = database()) {
            EffectiveCitizenPopulationPolicyRegistry policies = registry(database);
            scheduled = policies.schedule(request);
            assertEquals(scheduled, policies.schedule(request));
            assertTrue(policies.current(EFFECTIVE_AT.minusMillis(1L)).isEmpty());
            EffectiveCitizenPopulationPolicy current =
                    policies.current(EFFECTIVE_AT).orElseThrow().policy();
            assertEquals(Duration.ofDays(45), current.observationWindow());
            assertEquals(Duration.ofHours(6), current.fullContributionTime());
        }
        try (CivicDatabase reopened = database()) {
            assertEquals(scheduled, registry(reopened).current(EFFECTIVE_AT).orElseThrow());
        }
    }

    @Test
    void changedReplayCannotRewritePopulationInterpretationHistory() {
        try (CivicDatabase database = database()) {
            EffectiveCitizenPopulationPolicyRegistry policies = registry(database);
            ScheduleEffectiveCitizenPopulationPolicy original =
                    new ScheduleEffectiveCitizenPopulationPolicy(
                            SERVICE, "immutable-population-policy", "civic-admin-console:test",
                            new EffectiveCitizenPopulationPolicy(
                                    Duration.ofDays(60), Duration.ofHours(8)),
                            EFFECTIVE_AT, "Original Effective Citizen population policy");
            policies.schedule(original);
            assertThrows(org.civiceconomy.fiscal.IdempotencyConflictException.class,
                    () -> policies.schedule(new ScheduleEffectiveCitizenPopulationPolicy(
                            SERVICE, original.requestId(), original.actorIdentity(),
                            new EffectiveCitizenPopulationPolicy(
                                    Duration.ofDays(30), Duration.ofHours(8)),
                            original.effectiveAt(), original.reason())));
        }
    }

    private EffectiveCitizenPopulationPolicyRegistry registry(CivicDatabase database) {
        return new EffectiveCitizenPopulationPolicyRegistry(
                database, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("effective-citizen-population-policy.sqlite3"),
                new DatabaseIdentity(UUID.fromString("e05cf4b4-d9e0-46b0-9be0-d9189398c2af"),
                        "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20"));
    }
}
