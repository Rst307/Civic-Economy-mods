package org.civiceconomy.territory;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

class TerritoryFreeAllocationPolicyRegistryTest {
    private static final Instant NOW = Instant.parse("2026-07-14T12:00:00Z");
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-07-21T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void scheduledPolicyChangesOnlyAtEffectiveTimeAndSurvivesRestart() {
        TerritoryFreeAllocationPolicyVersion scheduled;
        ScheduleTerritoryFreeAllocationPolicy request =
                new ScheduleTerritoryFreeAllocationPolicy(
                        new ServiceIdentity("civiceconomy-policy"),
                        "territory-policy-july",
                        "civic-admin-console:test",
                        9,
                        4,
                        EFFECTIVE_AT,
                        "July territory policy");

        try (CivicDatabase database = database()) {
            TerritoryFreeAllocationPolicyRegistry policies = registry(database);

            scheduled = policies.schedule(request);

            assertEquals(scheduled, policies.schedule(request));
            assertEquals(0, policies.current(EFFECTIVE_AT.minusMillis(1)).baseChunks());
            assertEquals(0, policies.current(EFFECTIVE_AT.minusMillis(1)).chunksPerEffectiveCitizen());
            assertEquals(9, policies.current(EFFECTIVE_AT).baseChunks());
            assertEquals(4, policies.current(EFFECTIVE_AT).chunksPerEffectiveCitizen());
        }

        try (CivicDatabase reopened = database()) {
            TerritoryFreeAllocationPolicyRegistry policies = registry(reopened);

            assertEquals(scheduled, policies.current(EFFECTIVE_AT));
        }
    }

    private TerritoryFreeAllocationPolicyRegistry registry(CivicDatabase database) {
        return new TerritoryFreeAllocationPolicyRegistry(
                database,
                Clock.fixed(NOW, ZoneOffset.UTC),
                TerritoryFreeAllocationPolicyVersion.defaultPolicy(0, 0));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("territory-free-policy.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("2aac5469-8754-48aa-a76e-d0c1367f9b27"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
