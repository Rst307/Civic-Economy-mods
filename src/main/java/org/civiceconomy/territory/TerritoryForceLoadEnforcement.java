package org.civiceconomy.territory;

import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;

public record TerritoryForceLoadEnforcement(
        UUID enforcementId,
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID assessmentId,
        UUID ftbTeamId,
        TerritoryClaimPosition position,
        TerritoryForceLoadEnforcementState state,
        String reason,
        Instant notBefore,
        Instant preparedAt,
        Instant externalAppliedAt,
        Instant committedAt) {
    public TerritoryForceLoadEnforcement {
        if (enforcementId == null
                || serviceIdentity == null
                || requestId == null
                || assessmentId == null
                || ftbTeamId == null
                || position == null
                || state == null
                || reason == null
                || notBefore == null
                || preparedAt == null) {
            throw new IllegalArgumentException(
                    "Territory Force-load Enforcement cannot contain required null values");
        }
        if (requestId.isBlank() || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Territory Force-load Enforcement request and reason cannot be blank");
        }
        if (state == TerritoryForceLoadEnforcementState.PREPARED
                && (externalAppliedAt != null || committedAt != null)) {
            throw new IllegalArgumentException("PREPARED enforcement cannot have completion times");
        }
        if (state == TerritoryForceLoadEnforcementState.EXTERNAL_APPLIED
                && (externalAppliedAt == null || committedAt != null)) {
            throw new IllegalArgumentException(
                    "EXTERNAL_APPLIED enforcement requires only its external time");
        }
        if (state == TerritoryForceLoadEnforcementState.CIVIC_COMMITTED
                && (externalAppliedAt == null || committedAt == null)) {
            throw new IllegalArgumentException(
                    "CIVIC_COMMITTED enforcement requires both completion times");
        }
        if (externalAppliedAt != null && externalAppliedAt.isBefore(notBefore)) {
            throw new IllegalArgumentException(
                    "Territory Force-load Enforcement cannot precede its grace deadline");
        }
        if (committedAt != null && committedAt.isBefore(externalAppliedAt)) {
            throw new IllegalArgumentException(
                    "Territory Force-load Enforcement commit cannot precede external application");
        }
    }
}
