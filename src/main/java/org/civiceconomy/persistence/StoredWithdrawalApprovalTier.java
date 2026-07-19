package org.civiceconomy.persistence;

public record StoredWithdrawalApprovalTier(
        long minimumAmountMinorUnits,
        int requiredApprovals) {}
