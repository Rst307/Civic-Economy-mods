package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredTreasuryWithdrawalApprovalVote(
        UUID voteId,
        String serviceIdentity,
        String requestId,
        UUID approverPlayerId,
        String reason,
        long approvedAtEpochMillis) {}
