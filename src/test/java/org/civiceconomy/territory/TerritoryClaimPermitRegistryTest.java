package org.civiceconomy.territory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerritoryClaimPermitRegistryTest {
    private static final Instant NOW = Instant.parse("2026-07-14T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final NationId NATION_ID = new NationId(
            UUID.fromString("e51970c1-224b-4637-819b-bac69e58ff91"));
    private static final UUID TEAM_ID =
            UUID.fromString("c7a855b4-b071-449a-9290-797f0df2cd98");
    private static final UUID ACTOR_ID =
            UUID.fromString("496ec8f6-4696-456b-b270-0dadd805f503");
    private static final UUID PAYMENT_ID =
            UUID.fromString("8404d2b8-f653-41b7-a35d-78446ec5b01b");

    @TempDir
    Path temporaryDirectory;

    @Test
    void issuesOneReadyPermitOnlyAfterExactCommittedPrepaymentAndSurvivesRestart() {
        TerritoryClaimPermit issued;
        IssueTerritoryClaimPermit request = new IssueTerritoryClaimPermit(
                new ServiceIdentity("civiceconomy-territory"),
                "permit-overworld-4-7",
                NATION_ID,
                TEAM_ID,
                ACTOR_ID,
                "minecraft:overworld",
                4,
                7,
                16,
                17,
                250L,
                PAYMENT_ID,
                NOW.plusSeconds(120L));

        try (CivicDatabase database = database()) {
            registerNation(database);
            TerritoryClaimPermitRegistry permits = registry(database);

            issued = permits.issue(request);

            assertEquals(issued, permits.issue(request));
            assertEquals(TerritoryClaimPermitState.READY, issued.state());
            assertEquals(250L, issued.prepayment().minorUnits());
            assertEquals(PAYMENT_ID, issued.prepaymentTransactionId());
        }

        try (CivicDatabase reopened = database()) {
            TerritoryClaimPermitRegistry permits = registry(reopened);

            assertEquals(issued, permits.find(issued.permitId()).orElseThrow());
        }
    }

    @Test
    void consumesPermitExactlyOnceForItsBoundActorAndChunk() {
        try (CivicDatabase database = database()) {
            registerNation(database);
            TerritoryClaimPermitRegistry permits = registry(database);
            TerritoryClaimPermit issued = permits.issue(new IssueTerritoryClaimPermit(
                    new ServiceIdentity("civiceconomy-territory"),
                    "permit-consume-overworld-4-7",
                    NATION_ID,
                    TEAM_ID,
                    ACTOR_ID,
                    "minecraft:overworld",
                    4,
                    7,
                    16,
                    17,
                    250L,
                    PAYMENT_ID,
                    NOW.plusSeconds(120L)));
            ConsumeTerritoryClaimPermit request = new ConsumeTerritoryClaimPermit(
                    new ServiceIdentity("civiceconomy-ftb-claim"),
                    "consume-overworld-4-7",
                    issued.permitId(),
                    NATION_ID,
                    TEAM_ID,
                    ACTOR_ID,
                    "minecraft:overworld",
                    4,
                    7);

            TerritoryClaimPermit consumed = permits.consume(request);

            assertEquals(TerritoryClaimPermitState.CONSUMED, consumed.state());
            assertEquals(consumed, permits.consume(request));
        }

        try (CivicDatabase reopened = database()) {
            TerritoryClaimPermit consumed = registry(reopened)
                    .findByTarget(NATION_ID, "minecraft:overworld", 4, 7)
                    .orElseThrow();

            assertEquals(TerritoryClaimPermitState.CONSUMED, consumed.state());
        }
    }

    @Test
    void rejectsPermitWhenFiscalPrepaymentIsNotExactlyCommitted() {
        try (CivicDatabase database = database()) {
            registerNation(database);
            TerritoryClaimPermitRegistry permits = new TerritoryClaimPermitRegistry(
                    database, (transactionId, nationId, amount) -> false, CLOCK);
            IssueTerritoryClaimPermit request = new IssueTerritoryClaimPermit(
                    new ServiceIdentity("civiceconomy-territory"),
                    "permit-unpaid-overworld-4-7",
                    NATION_ID,
                    TEAM_ID,
                    ACTOR_ID,
                    "minecraft:overworld",
                    4,
                    7,
                    16,
                    17,
                    250L,
                    PAYMENT_ID,
                    NOW.plusSeconds(120L));

            assertThrows(SecurityException.class, () -> permits.issue(request));
            assertNull(database.territoryClaimPermit(
                    request.serviceIdentity().value(), request.requestId()));
        }
    }

    private TerritoryClaimPermitRegistry registry(CivicDatabase database) {
        TerritoryPrepaymentVerifier verifier = (transactionId, nationId, amount) ->
                transactionId.equals(PAYMENT_ID)
                        && nationId.equals(NATION_ID)
                        && amount.minorUnits() == 250L;
        return new TerritoryClaimPermitRegistry(database, verifier, CLOCK);
    }

    private void registerNation(CivicDatabase database) {
        if (database.nation(NATION_ID.value()) == null) {
            database.registerNation(
                    NATION_ID.value(),
                    "civiceconomy-territory-test",
                    "register-permit-nation",
                    TEAM_ID,
                    NOW.minusSeconds(60L).toEpochMilli());
        }
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("territory-claim-permit.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("322fa210-5fb2-4ce2-b20d-74a07d150e50"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
