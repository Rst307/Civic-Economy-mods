package org.civiceconomy.platform.neoforge;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("civiceconomy_budget_disbursement_restart")
@PrefixGameTestTemplate(false)
public final class BudgetDisbursementProcessRestartGameTests {
    private BudgetDisbursementProcessRestartGameTests() {}

    @GameTest(
            template = "empty",
            timeoutTicks = 12_000,
            batch = "budget-disbursement-process-restart")
    public static void recoversMatchedRealLcPaymentExactlyOnce(
            GameTestHelper helper) {
        if (BudgetDisbursementProcessRestartDrill.verifying()) {
            CivicServerRuntimeGameTests.verifyBudgetDisbursementProcessRestart(helper);
            return;
        }
        if (BudgetDisbursementProcessRestartDrill.preparing()) {
            CivicServerRuntimeGameTests.prepareBudgetDisbursementProcessRestart(helper);
            return;
        }
        helper.fail("Budget Disbursement process restart drill mode is missing");
    }
}
