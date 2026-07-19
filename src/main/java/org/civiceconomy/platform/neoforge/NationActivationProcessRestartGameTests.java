package org.civiceconomy.platform.neoforge;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("civiceconomy_nation_activation_restart")
@PrefixGameTestTemplate(false)
public final class NationActivationProcessRestartGameTests {
    private NationActivationProcessRestartGameTests() {}

    @GameTest(
            template = "empty",
            timeoutTicks = 12_000,
            batch = "nation-activation-process-restart")
    public static void recoversProvisionedTreasuryExactlyOnce(GameTestHelper helper) {
        if (NationActivationProcessRestartDrill.verifying()) {
            CivicServerRuntimeGameTests.verifyNationActivationProcessRestart(helper);
            return;
        }
        if (NationActivationProcessRestartDrill.preparing()) {
            CivicServerRuntimeGameTests.prepareNationActivationProcessRestart(helper);
            return;
        }
        helper.fail("Nation Activation process restart drill mode is missing");
    }
}
