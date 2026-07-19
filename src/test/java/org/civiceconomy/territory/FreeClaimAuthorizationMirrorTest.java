package org.civiceconomy.territory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.nation.NationId;
import org.junit.jupiter.api.Test;

class FreeClaimAuthorizationMirrorTest {
    private static final Instant NOW = Instant.parse("2026-07-14T16:00:00Z");
    private static final NationId NATION_ID = NationId.create();

    @Test
    void authorizesReadOnlyChecksAndAcquiresOnceAfterRealClaim() {
        TerritoryClaimTarget target = new TerritoryClaimTarget(
                NATION_ID,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "minecraft:overworld",
                7,
                9);
        FreeClaimAuthorization authorization = new FreeClaimAuthorization(
                UUID.randomUUID(), "free-claim-7-9", target, NOW.plusSeconds(120L));
        FreeClaimAuthorizationMirror mirror = new FreeClaimAuthorizationMirror();

        mirror.publish(authorization);

        assertTrue(mirror.authorizes(target, NOW));
        assertTrue(mirror.authorizes(target, NOW));
        assertEquals(1, mirror.pendingCount(NATION_ID, NOW));
        assertEquals(authorization, mirror.acquireAfterSuccessfulClaim(target, NOW).orElseThrow());
        assertFalse(mirror.authorizes(target, NOW));
        assertTrue(mirror.acquireAfterSuccessfulClaim(target, NOW).isEmpty());
        assertEquals(0, mirror.pendingCount(NATION_ID, NOW));
    }

    @Test
    void expiredAuthorizationFailsClosedAndDoesNotOccupyAllocation() {
        TerritoryClaimTarget target = new TerritoryClaimTarget(
                NATION_ID,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "minecraft:overworld",
                10,
                11);
        FreeClaimAuthorizationMirror mirror = new FreeClaimAuthorizationMirror();
        mirror.publish(new FreeClaimAuthorization(
                UUID.randomUUID(), "expired-free-claim", target, NOW));

        assertFalse(mirror.authorizes(target, NOW));
        assertEquals(0, mirror.pendingCount(NATION_ID, NOW));
    }

    @Test
    void consumedRequestCannotBeRepublished() {
        TerritoryClaimTarget target = new TerritoryClaimTarget(
                NATION_ID,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "minecraft:overworld",
                12,
                13);
        FreeClaimAuthorization authorization = new FreeClaimAuthorization(
                UUID.randomUUID(),
                "single-use-free-request",
                target,
                NOW.plusSeconds(120L));
        FreeClaimAuthorizationMirror mirror = new FreeClaimAuthorizationMirror();
        mirror.publish(authorization);
        mirror.acquireAfterSuccessfulClaim(target, NOW).orElseThrow();

        assertThrows(
                IllegalStateException.class,
                () -> mirror.requireActiveReplay(
                        "single-use-free-request", NOW));
        assertThrows(
                IllegalStateException.class,
                () -> mirror.publish(authorization));
    }
}
