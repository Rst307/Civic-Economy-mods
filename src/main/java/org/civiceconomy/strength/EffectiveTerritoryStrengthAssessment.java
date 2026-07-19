package org.civiceconomy.strength;

import java.util.List;
import java.util.UUID;
import org.civiceconomy.nation.NationId;

public record EffectiveTerritoryStrengthAssessment(
        NationId nationId,
        UUID ftbTeamId,
        boolean ownershipAvailable,
        List<EffectiveTerritoryClaimAssessment> claims) {
    public EffectiveTerritoryStrengthAssessment {
        if (nationId == null || ftbTeamId == null || claims == null
                || claims.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Effective Territory assessment is invalid");
        }
        claims = List.copyOf(claims);
    }

    public int currentClaimCount() {
        return claims.size();
    }

    public int effectiveClaimCount() {
        return count(EffectiveTerritoryClaimState.EFFECTIVE);
    }

    public int suspendedClaimCount() {
        return count(EffectiveTerritoryClaimState.SUSPENDED);
    }

    public int unassessedClaimCount() {
        return count(EffectiveTerritoryClaimState.UNASSESSED);
    }

    public boolean anomalous() {
        return !ownershipAvailable || unassessedClaimCount() > 0;
    }

    private int count(EffectiveTerritoryClaimState state) {
        return (int) claims.stream().filter(claim -> claim.state() == state).count();
    }
}
