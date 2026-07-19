package org.civiceconomy.fiscal;

public final class TreasuryWithdrawalApprovalPendingException
        extends IllegalStateException {
    private final TreasuryWithdrawalApproval approval;

    public TreasuryWithdrawalApprovalPendingException(
            TreasuryWithdrawalApproval approval) {
        super("Treasury Withdrawal " + approval.approvalRequestId()
                + " requires " + approval.requiredApprovals()
                + " distinct approvals; current="
                + approval.approverPlayerIds().size());
        this.approval = approval;
    }

    public TreasuryWithdrawalApproval approval() {
        return approval;
    }
}
