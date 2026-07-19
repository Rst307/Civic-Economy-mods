package org.civiceconomy.production;

public record ProductionStack(String itemId, String componentFingerprint, int count) {
    public static final ProductionStack EMPTY =
            new ProductionStack("minecraft:air", "components:{}", 0);

    public ProductionStack {
        if (itemId == null || itemId.isBlank()
                || componentFingerprint == null || componentFingerprint.isBlank()
                || count < 0) {
            throw new IllegalArgumentException("Production Stack is invalid");
        }
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public boolean sameIdentity(ProductionStack other) {
        return other != null
                && itemId.equals(other.itemId)
                && componentFingerprint.equals(other.componentFingerprint);
    }

    public ProductionStack withCount(int newCount) {
        return new ProductionStack(itemId, componentFingerprint, newCount);
    }
}
