package org.civiceconomy.mint;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;

public record MintRecipeVersion(
        UUID recipeVersionId,
        ServiceIdentity serviceIdentity,
        String requestId,
        int versionNumber,
        List<MintRecipeIngredient> ingredients,
        Duration processingDuration,
        String reason,
        Instant publishedAt) {
    public MintRecipeVersion {
        ingredients = List.copyOf(ingredients);
    }
}
