package org.civiceconomy.platform.neoforge;

import java.util.List;
import org.civiceconomy.fiscal.WithdrawalApprovalTier;

public final class WithdrawalApprovalTierParser {
    private WithdrawalApprovalTierParser() {}

    public static List<WithdrawalApprovalTier> parse(String specification) {
        return ApprovalTierParser.parse(specification, WithdrawalApprovalTier::new);
    }
}
