package org.civiceconomy.mint;

import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;

public record RegisteredMint(
        UUID mintId,
        ServiceIdentity serviceIdentity,
        String requestId,
        NationId nationId,
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
        Instant registeredAt) {}
