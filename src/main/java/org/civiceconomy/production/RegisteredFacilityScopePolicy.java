package org.civiceconomy.production;

public record RegisteredFacilityScopePolicy(int maxScopeChunks) {
    public RegisteredFacilityScopePolicy {
        if (maxScopeChunks <= 0) {
            throw new IllegalArgumentException(
                    "Registered Facility Scope policy maximum must be positive");
        }
    }
}
