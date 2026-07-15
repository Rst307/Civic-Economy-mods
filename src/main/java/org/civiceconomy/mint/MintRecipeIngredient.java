package org.civiceconomy.mint;

import org.civiceconomy.fiscal.MoneyAmount;

public record MintRecipeIngredient(
        int groupIndex,
        MintIngredientMatcherKind matcherKind,
        String matcherValue,
        long quantityUnits,
        MoneyAmount perFaceValue) {
    public MintRecipeIngredient {
        if (groupIndex < 0
                || matcherKind == null
                || matcherValue == null
                || matcherValue.isBlank()
                || !matcherValue.contains(":")
                || quantityUnits <= 0L
                || perFaceValue == null) {
            throw new IllegalArgumentException("Mint Recipe ingredient is invalid");
        }
    }

    public boolean fixedQuantity() {
        return perFaceValue.equals(MoneyAmount.ZERO);
    }
}
