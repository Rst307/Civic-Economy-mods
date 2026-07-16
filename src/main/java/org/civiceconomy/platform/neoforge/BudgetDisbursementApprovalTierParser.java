package org.civiceconomy.platform.neoforge;

import java.util.List;
import org.civiceconomy.fiscal.BudgetDisbursementApprovalTier;

public final class BudgetDisbursementApprovalTierParser {
    private BudgetDisbursementApprovalTierParser() {}

    public static List<BudgetDisbursementApprovalTier> parse(String specification) {
        return ApprovalTierParser.parse(
                specification, BudgetDisbursementApprovalTier::new);
    }
}
