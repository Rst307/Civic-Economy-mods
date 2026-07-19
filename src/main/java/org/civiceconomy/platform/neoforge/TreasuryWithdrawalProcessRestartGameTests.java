package org.civiceconomy.platform.neoforge;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("civiceconomy_treasury_withdrawal_restart")
@PrefixGameTestTemplate(false)
public final class TreasuryWithdrawalProcessRestartGameTests {
    private TreasuryWithdrawalProcessRestartGameTests() {}

    @GameTest(
            template = "empty",
            timeoutTicks = 12_000,
            batch = "treasury-withdrawal-process-restart")
    public static void recoversMatchedCashDeliveryExactlyOnce(
            GameTestHelper helper) {
        if (TreasuryWithdrawalProcessRestartDrill.verifying()) {
            CivicServerRuntimeGameTests.verifyTreasuryWithdrawalProcessRestart(helper);
            return;
        }
        if (TreasuryWithdrawalProcessRestartDrill.preparing()) {
            CivicServerRuntimeGameTests.prepareTreasuryWithdrawalProcessRestart(helper);
            return;
        }
        helper.fail("Treasury Withdrawal process restart drill mode is missing");
    }
}
