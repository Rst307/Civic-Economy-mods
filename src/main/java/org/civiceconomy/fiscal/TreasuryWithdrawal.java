package org.civiceconomy.fiscal;

import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.nation.NationId;

public record TreasuryWithdrawal(
        UUID withdrawalId,
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID approvalRequestId,
        NationId nationId,
        AccountId sourceAccount,
        UUID actorPlayerId,
        MoneyAmount amount,
        String reason,
        String state,
        Instant preparedAt,
        Instant committedAt) {}
