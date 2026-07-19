package org.civiceconomy.strength;

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
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MintCompliancePolicyRegistryTest {
    private static final Instant NOW = Instant.parse("2026-07-19T05:00:00Z");
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-07-26T00:00:00Z");
    private static final ServiceIdentity SERVICE =
            new ServiceIdentity("civiceconomy-mint-compliance-policy");

    @TempDir Path temporaryDirectory;

    @Test
    void scheduledPolicyChangesOnlyAtEffectiveTimeAndSurvivesRestart() {
        ScheduleMintCompliancePolicy request = new ScheduleMintCompliancePolicy(
                SERVICE,
                "initial-mint-compliance-policy",
                "civic-admin-console:test",
                new MintCompliancePolicy(Duration.ofDays(30), 5_000),
                EFFECTIVE_AT,
                "Initial Mint Compliance policy");
        MintCompliancePolicyVersion scheduled;

        try (CivicDatabase database = database()) {
            MintCompliancePolicyRegistry policies = registry(database);

            scheduled = policies.schedule(request);

            assertEquals(scheduled, policies.schedule(request));
            assertTrue(policies.current(EFFECTIVE_AT.minusMillis(1L)).isEmpty());
            assertEquals(scheduled, policies.current(EFFECTIVE_AT).orElseThrow());
        }

        try (CivicDatabase reopened = database()) {
            assertEquals(scheduled, registry(reopened).current(EFFECTIVE_AT).orElseThrow());
        }
    }

    @Test
    void changedRequestReplayCannotRewritePolicyHistory() {
        try (CivicDatabase database = database()) {
            MintCompliancePolicyRegistry policies = registry(database);
            ScheduleMintCompliancePolicy original = new ScheduleMintCompliancePolicy(
                    SERVICE,
                    "immutable-mint-compliance-policy",
                    "civic-admin-console:test",
                    new MintCompliancePolicy(Duration.ofDays(30), 5_000),
                    EFFECTIVE_AT,
                    "Original policy");
            policies.schedule(original);

            assertThrows(
                    IdempotencyConflictException.class,
                    () -> policies.schedule(new ScheduleMintCompliancePolicy(
                            SERVICE,
                            original.requestId(),
                            original.actorIdentity(),
                            new MintCompliancePolicy(Duration.ofDays(7), 2_500),
                            original.effectiveAt(),
                            original.reason())));
        }
    }

    private MintCompliancePolicyRegistry registry(CivicDatabase database) {
        return new MintCompliancePolicyRegistry(database, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("mint-compliance-policy.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("f354f09a-8722-473b-828e-98a74375943c"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
