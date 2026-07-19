package org.civiceconomy.mint;

import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;

public record RegisterMint(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID mintId,
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
        String reason) {
    public RegisterMint {
        if (serviceIdentity == null
                || mintId == null
                || nationId == null
                || operatorOrganizationId == null
                || licenseId == null
                || recipeVersionId == null
                || actorPlayerId == null) {
            throw new IllegalArgumentException("Registered Mint request cannot contain null values");
        }
        if (requestId == null
                || requestId.isBlank()
                || dimensionId == null
                || dimensionId.isBlank()
                || reason == null
                || reason.isBlank()) {
            throw new IllegalArgumentException("Registered Mint request values are invalid");
        }
    }
}
