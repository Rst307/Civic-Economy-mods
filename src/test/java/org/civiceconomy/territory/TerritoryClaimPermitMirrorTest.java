package org.civiceconomy.territory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.nation.NationId;
import org.junit.jupiter.api.Test;

class TerritoryClaimPermitMirrorTest {
    private static final Instant NOW = Instant.parse("2026-07-14T15:00:00Z");
    private static final NationId NATION_ID = new NationId(
            UUID.fromString("00dc543b-a7e4-44f0-aa40-484443fb0592"));
    private static final UUID TEAM_ID =
            UUID.fromString("d252e41f-505f-4091-9f08-18de43f79c8b");
    private static final UUID ACTOR_ID =
            UUID.fromString("48a16899-375e-4b06-bc45-87c75ca26f3b");

    @Test
    void repeatedBeforeChecksNeverConsumeAndOneRealAfterClaimAcquiresPermit() {
        TerritoryClaimPermitMirror mirror = new TerritoryClaimPermitMirror();
        TerritoryClaimTarget target = new TerritoryClaimTarget(
                NATION_ID,
                TEAM_ID,
                ACTOR_ID,
                "minecraft:overworld",
                9,
                14);
        TerritoryClaimPermit permit = permit(target);
        mirror.publish(permit);

        assertTrue(mirror.authorizes(target, NOW));
        assertTrue(mirror.authorizes(target, NOW));
        assertTrue(mirror.authorizes(target, NOW));

        assertEquals(permit, mirror.acquireAfterSuccessfulClaim(target, NOW).orElseThrow());
        assertTrue(mirror.acquireAfterSuccessfulClaim(target, NOW).isEmpty());
        assertFalse(mirror.authorizes(target, NOW));
    }

    private TerritoryClaimPermit permit(TerritoryClaimTarget target) {
        return new TerritoryClaimPermit(
                UUID.fromString("539d2692-b7c1-424b-8729-5e68a1460cdc"),
                target.nationId(),
                target.ftbTeamId(),
                target.actorPlayerId(),
                target.dimensionId(),
                target.chunkX(),
                target.chunkZ(),
                17,
                17,
                MoneyAmount.ofMinorUnits(250L),
                UUID.fromString("ae0740fd-14ac-4cc8-89b5-f3aa56dad769"),
                TerritoryClaimPermitState.READY,
                NOW.minusSeconds(10L),
                NOW.plusSeconds(120L));
    }
}
