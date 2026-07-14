package org.civiceconomy.territory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.nation.NationId;
import org.junit.jupiter.api.Test;

class TerritoryClaimPermitEventBridgeTest {
    private static final Instant NOW = Instant.parse("2026-07-14T15:30:00Z");
    private static final NationId NATION_ID = new NationId(
            UUID.fromString("d23c6d2a-78c8-40ac-bc3c-e92e3cda6904"));
    private static final UUID TEAM_ID =
            UUID.fromString("f8768d16-0c21-43cc-9801-385103d33123");
    private static final UUID ACTOR_ID =
            UUID.fromString("067a7c7b-e53a-4894-917c-d6a288c92ca0");

    @Test
    void beforeIsReadOnlyAndAfterQueuesOneDurableConsumption() {
        TerritoryClaimPermitMirror mirror = new TerritoryClaimPermitMirror();
        TerritoryClaimTarget target = new TerritoryClaimTarget(
                NATION_ID, TEAM_ID, ACTOR_ID, "minecraft:overworld", 21, 34);
        mirror.publish(permit(target));
        List<TerritoryClaimPermitConsumptionIntent> queued = new ArrayList<>();
        TerritoryClaimPermitEventBridge bridge = new TerritoryClaimPermitEventBridge(
                mirror,
                queued::add,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertTrue(bridge.beforeClaim(target));
        assertTrue(bridge.beforeClaim(target));
        assertTrue(queued.isEmpty());

        assertTrue(bridge.afterSuccessfulClaim(target));
        assertFalse(bridge.afterSuccessfulClaim(target));
        assertTrue(queued.size() == 1);
        assertTrue(queued.getFirst().permitId().equals(
                UUID.fromString("9fb92dd8-9e2e-44d2-8fe0-a9c67806fe64")));
    }

    private TerritoryClaimPermit permit(TerritoryClaimTarget target) {
        return new TerritoryClaimPermit(
                UUID.fromString("9fb92dd8-9e2e-44d2-8fe0-a9c67806fe64"),
                target.nationId(),
                target.ftbTeamId(),
                target.actorPlayerId(),
                target.dimensionId(),
                target.chunkX(),
                target.chunkZ(),
                17,
                17,
                MoneyAmount.ofMinorUnits(250L),
                UUID.fromString("eb60b42e-6951-41ee-a11f-3d7b61f6ad51"),
                TerritoryClaimPermitState.READY,
                NOW.minusSeconds(1L),
                NOW.plusSeconds(120L));
    }
}
