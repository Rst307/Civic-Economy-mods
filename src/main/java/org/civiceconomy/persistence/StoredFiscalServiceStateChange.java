package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredFiscalServiceStateChange(
        UUID changeId,
        String administratorIdentity,
        String requestId,
        String serviceIdentity,
        String state,
        String reason,
        long changedAtEpochMillis) {}
