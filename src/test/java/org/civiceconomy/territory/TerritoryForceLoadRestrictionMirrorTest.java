package org.civiceconomy.territory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.nation.NationId;
import org.junit.jupiter.api.Test;

class TerritoryForceLoadRestrictionMirrorTest {
    private static final NationId NATION = new NationId(
            UUID.fromString("dc7f9a92-c662-49e4-b0b2-6149ef6754cf"));
    private static final UUID TEAM = UUID.fromString("e752368e-5ec6-4530-b1f6-a70812aa25d3");
    private static final TerritoryClaimPosition POSITION =
            new TerritoryClaimPosition("minecraft:overworld", 8, 13);

    @Test
    void atomicSnapshotBlocksOnlyTheExactTeamAndClaimAndCanReleaseIt() {
        TerritoryForceLoadRestrictionMirror mirror = new TerritoryForceLoadRestrictionMirror();
        mirror.replaceAll(List.of(new TerritoryForceLoadRestriction(
                UUID.fromString("0de3235e-8108-4128-801f-3ca2e0759a97"),
                NATION,
                TEAM,
                POSITION,
                Instant.ofEpochMilli(10_000L))));

        assertTrue(mirror.blocks(TEAM, POSITION));
        assertFalse(mirror.blocks(UUID.randomUUID(), POSITION));
        assertFalse(mirror.blocks(
                TEAM, new TerritoryClaimPosition("minecraft:overworld", 8, 14)));

        mirror.replaceAll(List.of());
        assertFalse(mirror.blocks(TEAM, POSITION));
    }
}
