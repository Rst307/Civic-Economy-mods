package org.civiceconomy.strength;

import org.civiceconomy.territory.TerritoryClaimPosition;

public record EffectiveTerritoryClaimAssessment(
        TerritoryClaimPosition position, EffectiveTerritoryClaimState state) {
    public EffectiveTerritoryClaimAssessment {
        if (position == null || state == null) {
            throw new IllegalArgumentException("Effective Territory Claim assessment is invalid");
        }
    }
}
