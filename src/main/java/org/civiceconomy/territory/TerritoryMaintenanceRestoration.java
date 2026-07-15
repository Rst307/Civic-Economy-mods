package org.civiceconomy.territory;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;

public record TerritoryMaintenanceRestoration(
        UUID restorationId,
        ServiceIdentity serviceIdentity,
        String requestId,
        NationId nationId,
        UUID ftbTeamId,
        UUID actorPlayerId,
        String dimensionId,
        int chunkX,
        int chunkZ,
        UUID sourceSuspendedAssessmentId,
        UUID policyId,
        Instant nextFullCycleStartsAt,
        MoneyAmount nextCyclePrepayment,
        MoneyAmount restorationFee,
        MoneyAmount totalDue,
        MoneyAmount remainingNextCycleCredit,
        Instant cooldownEndsAt,
        int destructionBasisPoints,
        TerritoryMaintenanceRestorationState state,
        Optional<UUID> reservationId,
        Optional<UUID> publicFundPaymentId,
        Optional<UUID> destructionOperationId,
        String reason,
        Instant preparedAt,
        Optional<Instant> committedAt) {
    public TerritoryMaintenanceRestoration {
        if (restorationId == null
                || serviceIdentity == null
                || requestId == null
                || nationId == null
                || ftbTeamId == null
                || actorPlayerId == null
                || dimensionId == null
                || sourceSuspendedAssessmentId == null
                || policyId == null
                || nextFullCycleStartsAt == null
                || nextCyclePrepayment == null
                || restorationFee == null
                || totalDue == null
                || remainingNextCycleCredit == null
                || cooldownEndsAt == null
                || state == null
                || reservationId == null
                || publicFundPaymentId == null
                || destructionOperationId == null
                || reason == null
                || preparedAt == null
                || committedAt == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Restoration cannot contain null values");
        }
        if (!totalDue.equals(nextCyclePrepayment.plus(restorationFee))
                || remainingNextCycleCredit.minorUnits()
                        > nextCyclePrepayment.minorUnits()) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Restoration amounts are inconsistent");
        }
    }
}
