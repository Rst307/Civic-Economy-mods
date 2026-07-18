package org.civiceconomy.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductionMarginalReturnPolicyRegistryTest {
    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-07-25T00:00:00Z");
    private static final ServiceIdentity SERVICE =
            new ServiceIdentity("civiceconomy-production-policy");

    @TempDir
    Path temporaryDirectory;

    @Test
    void scheduledPolicyChangesOnlyAtEffectiveTimeAndSurvivesRestart() {
        ScheduleProductionMarginalReturnPolicy request = request(5_000);
        ProductionMarginalReturnPolicyVersion scheduled;

        try (CivicDatabase database = database()) {
            ProductionMarginalReturnPolicyRegistry policies = registry(database);

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
    void changedPolicyReplayFailsWithoutReplacingTheOriginalVersion() {
        try (CivicDatabase database = database()) {
            ProductionMarginalReturnPolicyRegistry policies = registry(database);
            ProductionMarginalReturnPolicyVersion original = policies.schedule(request(5_000));

            assertThrows(
                    IdempotencyConflictException.class,
                    () -> policies.schedule(request(4_999)));
            assertEquals(original, policies.current(EFFECTIVE_AT).orElseThrow());
        }
    }

    private static ScheduleProductionMarginalReturnPolicy request(
            int facilityExcessWeightBasisPoints) {
        return new ScheduleProductionMarginalReturnPolicy(
                SERVICE,
                "initial-production-marginal-return-policy",
                "civic-admin-console:test",
                new ProductionMarginalReturnPolicy(
                        100_000L,
                        facilityExcessWeightBasisPoints,
                        500_000L,
                        2_500),
                EFFECTIVE_AT,
                "Initial production marginal-return policy");
    }

    private ProductionMarginalReturnPolicyRegistry registry(CivicDatabase database) {
        return new ProductionMarginalReturnPolicyRegistry(
                database,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("production-marginal-return-policy.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("239e98c7-f168-43e1-b49e-c2fe80c9b472"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
