package org.civiceconomy.persistence;

public record StoredFiscalService(
        String serviceIdentity,
        String ownerModId,
        String displayName,
        String administratorIdentity,
        String requestId,
        String reason,
        long registeredAtEpochMillis) {}
