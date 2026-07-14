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

class TerritoryExpansionPricingPolicyRegistryTest {
    private static final Instant NOW = Instant.parse("2026-07-14T12:00:00Z");
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-07-21T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void scheduledPricingChangesOnlyAtEffectiveTimeAndSurvivesRestart() {
        TerritoryExpansionPricingPolicyVersion scheduled;
        ScheduleTerritoryExpansionPricingPolicy request =
                new ScheduleTerritoryExpansionPricingPolicy(
                        new ServiceIdentity("civiceconomy-policy"),
                        "territory-pricing-july",
                        "civic-admin-console:test",
                        100L,
                        50L,
                        EFFECTIVE_AT,
                        "July expansion pricing");

        try (CivicDatabase database = database()) {
            TerritoryExpansionPricingPolicyRegistry pricing = registry(database);

            scheduled = pricing.schedule(request);

            assertEquals(scheduled, pricing.schedule(request));
            assertEquals(0L, pricing.current(EFFECTIVE_AT.minusMillis(1)).firstOverageChunkCost());
            assertEquals(0L, pricing.current(EFFECTIVE_AT.minusMillis(1)).additionalMarginalCost());
            assertEquals(100L, pricing.current(EFFECTIVE_AT).firstOverageChunkCost());
            assertEquals(50L, pricing.current(EFFECTIVE_AT).additionalMarginalCost());
        }

        try (CivicDatabase reopened = database()) {
            assertEquals(scheduled, registry(reopened).current(EFFECTIVE_AT));
        }
    }

    private TerritoryExpansionPricingPolicyRegistry registry(CivicDatabase database) {
        return new TerritoryExpansionPricingPolicyRegistry(
                database,
                Clock.fixed(NOW, ZoneOffset.UTC),
                TerritoryExpansionPricingPolicyVersion.defaultPolicy(0L, 0L));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("territory-expansion-pricing.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("4bf34f77-3ef4-4dce-885c-bd4babcc36bd"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
