package org.civiceconomy.platform.neoforge;

import org.civiceconomy.fiscal.WithdrawalApprovalPolicyVersion;

final class WithdrawalApprovalPolicyFormatter {
    private WithdrawalApprovalPolicyFormatter() {}

    static String format(WithdrawalApprovalPolicyVersion policy) {
        return "Withdrawal Approval Policy " + policy.policyId()
                + " nation=" + policy.nationId().value()
                + " effectiveAt=" + policy.effectiveAt()
                + " approvalLifetime=" + policy.approvalLifetime()
                + " actor=" + policy.actorPlayerId()
                + " reason=\"" + policy.reason() + "\""
                + " tiers=" + policy.tiers()
                + (policy.defaultPolicy() ? " [CONSERVATIVE DEFAULT]" : "");
    }
}
