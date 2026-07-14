package org.civiceconomy.persistence;

public record StoredFiscalService(
        String serviceIdentity,
        String ownerModId,
        String displayName,
        long registeredAtEpochMillis) {}
