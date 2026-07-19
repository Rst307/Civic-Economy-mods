package org.civiceconomy.persistence;

public record StoredBudgetDisbursementApprovalTier(
        long minimumAmountMinorUnits, int requiredApprovals) {}
