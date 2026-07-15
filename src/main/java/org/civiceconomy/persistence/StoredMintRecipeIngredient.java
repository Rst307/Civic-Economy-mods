package org.civiceconomy.persistence;

public record StoredMintRecipeIngredient(
        int groupIndex,
        String matcherKind,
        String matcherValue,
        long quantityUnits,
        long perFaceValueMinorUnits) {
    public StoredMintRecipeIngredient {
        if (groupIndex < 0
                || !("EXACT_ITEM".equals(matcherKind) || "TAG".equals(matcherKind))
                || matcherValue == null
                || matcherValue.isBlank()
                || !matcherValue.contains(":")
                || quantityUnits <= 0L
                || perFaceValueMinorUnits < 0L) {
            throw new IllegalArgumentException("Mint Recipe ingredient is invalid");
        }
    }
}
