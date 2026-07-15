package org.civiceconomy.mint;

public record MintMaterialStack(
        int groupIndex,
        String matcherKind,
        String matcherValue,
        String itemId,
        long count) {
    public MintMaterialStack {
        if (groupIndex < 0
                || !("EXACT_ITEM".equals(matcherKind) || "TAG".equals(matcherKind))
                || matcherValue == null || matcherValue.isBlank()
                || itemId == null || itemId.isBlank()
                || count <= 0L) {
            throw new IllegalArgumentException("Mint material stack is invalid");
        }
    }
}