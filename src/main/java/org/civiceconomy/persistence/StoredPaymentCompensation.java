package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredPaymentCompensation(
        UUID compensationId,
        String serviceIdentity,
        String requestId,
        UUID transactionId,
        String reason,
        String state) {}
