package org.civiceconomy.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GlobalReferencePriceRegistryTest {
    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-07-24T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void scheduledExactPriceChangesOnlyAtEffectiveTimeAndSurvivesRestart() {
        ScheduleGlobalReferencePrice request = new ScheduleGlobalReferencePrice(
                new ServiceIdentity("civiceconomy-reference-price"),
                "iron-ingot-initial-price",
                "civic-admin-console:test",
                "minecraft:iron_ingot",
                "components:{}",
                25L,
                EFFECTIVE_AT,
                "Initial server reference price");
        GlobalReferencePriceVersion scheduled;

        try (CivicDatabase database = database()) {
            GlobalReferencePriceRegistry prices = registry(database);

            scheduled = prices.schedule(request);

            assertEquals(scheduled, prices.schedule(request));
            assertTrue(prices.current(
                    "minecraft:iron_ingot",
                    "components:{}",
                    EFFECTIVE_AT.minusMillis(1L)).isEmpty());
            assertEquals(25L, prices.current(
                    "minecraft:iron_ingot",
                    "components:{}",
                    EFFECTIVE_AT).orElseThrow().unitPriceMinorUnits());
        }

        try (CivicDatabase reopened = database()) {
            assertEquals(scheduled, registry(reopened).current(
                    "minecraft:iron_ingot",
                    "components:{}",
                    EFFECTIVE_AT).orElseThrow());
        }
    }

    @Test
    void priceLookupRequiresTheExactComponentIdentity() {
        try (CivicDatabase database = database()) {
            GlobalReferencePriceRegistry prices = registry(database);
            prices.schedule(request(25L));

            assertEquals(25L, prices.current(
                    new ProductionStack("minecraft:iron_ingot", "components:{}", 1),
                    EFFECTIVE_AT).orElseThrow().unitPriceMinorUnits());
            assertTrue(prices.current(
                    new ProductionStack(
                            "minecraft:iron_ingot",
                            "components:{minecraft:custom_name=forged}",
                            1),
                    EFFECTIVE_AT).isEmpty());
            assertTrue(prices.current(
                    new ProductionStack("minecraft:gold_ingot", "components:{}", 1),
                    EFFECTIVE_AT).isEmpty());
        }
    }

    @Test
    void changedPriceReplayFailsWithoutAddingAnotherVersion() {
        try (CivicDatabase database = database()) {
            GlobalReferencePriceRegistry prices = registry(database);
            GlobalReferencePriceVersion original = prices.schedule(request(25L));

            assertThrows(IdempotencyConflictException.class, () ->
                    prices.schedule(request(26L)));
            assertEquals(original, prices.current(
                    "minecraft:iron_ingot", "components:{}", EFFECTIVE_AT)
                    .orElseThrow());
        }
    }

    private static ScheduleGlobalReferencePrice request(long unitPriceMinorUnits) {
        return new ScheduleGlobalReferencePrice(
                new ServiceIdentity("civiceconomy-reference-price"),
                "iron-ingot-initial-price",
                "civic-admin-console:test",
                "minecraft:iron_ingot",
                "components:{}",
                unitPriceMinorUnits,
                EFFECTIVE_AT,
                "Initial server reference price");
    }

    private GlobalReferencePriceRegistry registry(CivicDatabase database) {
        return new GlobalReferencePriceRegistry(
                database,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("global-reference-price.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("d4ebadbf-e61a-456b-9987-9025c251fd5b"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
