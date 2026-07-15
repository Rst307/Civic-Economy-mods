package org.civiceconomy.territory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerritoryMaintenancePolicyRegistryTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir Path temporaryDirectory;

    @Test
    void scheduledPolicyIsAbsentUntilEffectiveAndReplaysAcrossRestart() {
        ScheduleTerritoryMaintenancePolicy request = request(100L);
        TerritoryMaintenancePolicyVersion scheduled;
        try (CivicDatabase database = database()) {
            TerritoryMaintenancePolicyRegistry policies = registry(database);
            assertTrue(policies.current(NOW).isEmpty());
            scheduled = policies.schedule(request);
            assertTrue(policies.current(request.effectiveAt().minusMillis(1)).isEmpty());
            assertEquals(scheduled, policies.schedule(request));
        }

        try (CivicDatabase database = database()) {
            TerritoryMaintenancePolicyVersion current = registry(database)
                    .current(request.effectiveAt())
                    .orElseThrow();
            assertEquals(scheduled.policyId(), current.policyId());
            assertEquals(Duration.ofDays(7), current.cycleDuration());
            assertEquals(100L, current.baseMaintenancePerChargeableClaimMinorUnits());
            assertEquals(15_000, current.enclaveAndCrossDimensionMultiplierBasisPoints());
            assertEquals(25L, current.forceLoadSurchargeMinorUnits());
            assertEquals(6_000, current.destructionBasisPoints());
        }
    }

    @Test
    void changedReplayAndImmediateEffectFailClosed() {
        try (CivicDatabase database = database()) {
            TerritoryMaintenancePolicyRegistry policies = registry(database);
            policies.schedule(request(100L));
            assertThrows(IdempotencyConflictException.class, () -> policies.schedule(request(101L)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> policies.schedule(new ScheduleTerritoryMaintenancePolicy(
                            new ServiceIdentity("civiceconomy-territory-maintenance-policy"),
                            "immediate",
                            "console",
                            Duration.ofDays(7),
                            100L,
                            15_000,
                            25L,
                            6_000,
                            NOW,
                            "Immediate policy")));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ScheduleTerritoryMaintenancePolicy(
                            new ServiceIdentity("civiceconomy-territory-maintenance-policy"),
                            "sub-millisecond",
                            "console",
                            Duration.ofNanos(1),
                            100L,
                            15_000,
                            25L,
                            6_000,
                            NOW.plusSeconds(1),
                            "Invalid duration"));
        }
    }

    @Test
    void policyCannotTakeEffectInsideAnExistingMaintenanceCycle() {
        try (CivicDatabase database = database()) {
            database.openTerritoryMaintenanceCycle(
                    UUID.randomUUID(),
                    "civiceconomy-territory",
                    "existing-cycle",
                    NOW.plusSeconds(30).toEpochMilli(),
                    NOW.plusSeconds(120).toEpochMilli(),
                    NOW.toEpochMilli());

            assertThrows(
                    IllegalArgumentException.class,
                    () -> registry(database).schedule(request(100L)));
        }
    }

    private ScheduleTerritoryMaintenancePolicy request(long baseMaintenance) {
        return new ScheduleTerritoryMaintenancePolicy(
                new ServiceIdentity("civiceconomy-territory-maintenance-policy"),
                "maintenance-policy-v1",
                "console",
                Duration.ofDays(7),
                baseMaintenance,
                15_000,
                25L,
                6_000,
                NOW.plusSeconds(60),
                "Initial maintenance policy");
    }

    private TerritoryMaintenancePolicyRegistry registry(CivicDatabase database) {
        return new TerritoryMaintenancePolicyRegistry(
                database, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("maintenance-policy.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("b633a340-dd3d-4842-ad6c-64f22f84de6d"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
