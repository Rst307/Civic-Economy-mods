package org.civiceconomy.platform.neoforge;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("civiceconomy_permanent_destruction_restart")
@PrefixGameTestTemplate(false)
public final class PermanentDestructionProcessRestartGameTests {
    private PermanentDestructionProcessRestartGameTests() {}

    @GameTest(
            template = "empty",
            timeoutTicks = 12_000,
            batch = "permanent-destruction-process-restart")
    public static void recoversMatchedPermanentDestructionExactlyOnce(
            GameTestHelper helper) {
        if (PermanentDestructionProcessRestartDrill.verifying()) {
            CivicServerRuntimeGameTests.verifyPermanentDestructionProcessRestart(helper);
            return;
        }
        if (PermanentDestructionProcessRestartDrill.preparing()) {
            CivicServerRuntimeGameTests.preparePermanentDestructionProcessRestart(helper);
            return;
        }
        helper.fail("Permanent Destruction process restart drill mode is missing");
    }
}
