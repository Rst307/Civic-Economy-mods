package org.civiceconomy.nation;

import java.util.UUID;

public record EffectiveCitizenContribution(
        UUID playerId, NationId nationId, long attributedOnlineMillis, double contribution) {
    public EffectiveCitizenContribution {
        if (playerId == null || nationId == null) {
            throw new IllegalArgumentException("Effective Citizen identity cannot be null");
        }
        if (attributedOnlineMillis < 0 || !Double.isFinite(contribution)
                || contribution < 0D || contribution > 1D) {
            throw new IllegalArgumentException("Invalid Effective Citizen contribution");
        }
    }

    public boolean effective() {
        return contribution > 0D;
    }
}
