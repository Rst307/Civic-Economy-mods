package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredRegisteredMint(
        UUID mintId,
        String serviceIdentity,
        String requestId,
        UUID nationId,
        String dimensionId,
        int blockX,
        int blockY,
        int blockZ,
        UUID operatorOrganizationId,
        UUID licenseId,
        boolean automationAllowed,
        UUID recipeVersionId,
        UUID actorPlayerId,
        String transactionState,
        String reason,
        long registeredAtEpochMillis) {}
