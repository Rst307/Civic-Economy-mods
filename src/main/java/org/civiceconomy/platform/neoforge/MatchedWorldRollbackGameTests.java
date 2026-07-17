package org.civiceconomy.platform.neoforge;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("civiceconomy_matched_world_rollback")
@PrefixGameTestTemplate(false)
public final class MatchedWorldRollbackGameTests {
    private MatchedWorldRollbackGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 12_000, batch = "matched-world-rollback")
    public static void restoresMatchedWorldAndCivicDatabase(GameTestHelper helper) {
        if (MatchedWorldRollbackDrill.preparing()) {
            CivicServerRuntimeGameTests.prepareMatchedWorldRollback(helper);
            return;
        }
        if (MatchedWorldRollbackDrill.verifying()) {
            CivicServerRuntimeGameTests.verifyMatchedWorldRollback(helper);
            return;
        }
        helper.fail("Matched world rollback drill mode is missing");
    }
}
