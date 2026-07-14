package org.civiceconomy.fiscal;

import java.time.Instant;
import java.util.UUID;

public record LedgerEntry(
        UUID entryId,
        UUID transactionId,
        AccountId account,
        AccountId counterpartyAccount,
        MoneyAmount amount,
        LedgerDirection direction,
        PaymentKind transactionKind,
        Instant recordedAt) {}
