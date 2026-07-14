package org.civiceconomy.fiscal;

import java.time.Instant;
import java.util.UUID;

public record FiscalServiceStateChange(
        UUID changeId,
        ServiceIdentity administrator,
        String requestId,
        ServiceIdentity serviceIdentity,
        FiscalServiceState state,
        String reason,
        Instant changedAt) {}
