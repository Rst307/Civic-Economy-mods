package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredLedgerEntry(
        UUID entryId,
        UUID transactionId,
        String account,
        String counterpartyAccount,
        long amountMinorUnits,
        String direction,
        String transactionKind,
        long recordedAtEpochMillis) {}
