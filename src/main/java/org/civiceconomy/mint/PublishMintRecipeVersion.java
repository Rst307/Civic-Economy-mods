package org.civiceconomy.mint;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;

public record PublishMintRecipeVersion(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID recipeVersionId,
        int versionNumber,
        List<MintRecipeIngredient> ingredients,
        Duration processingDuration,
        String reason) {
    public PublishMintRecipeVersion {
        if (serviceIdentity == null
                || recipeVersionId == null
                || ingredients == null
                || processingDuration == null) {
            throw new IllegalArgumentException("Mint Recipe Version request cannot contain null values");
        }
        ingredients = List.copyOf(ingredients);
        if (requestId == null
                || requestId.isBlank()
                || versionNumber <= 0
                || ingredients.isEmpty()
                || processingDuration.toMillis() <= 0L
                || reason == null
                || reason.isBlank()) {
            throw new IllegalArgumentException("Mint Recipe Version request values are invalid");
        }
    }
}
