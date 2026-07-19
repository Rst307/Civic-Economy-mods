package org.civiceconomy.territory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredTerritoryMaintenanceRestoration;

public final class TerritoryMaintenanceRestorationRegistry {
    private final CivicDatabase database;
    private final Clock clock;

    public TerritoryMaintenanceRestorationRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Restoration dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public TerritoryMaintenanceRestoration prepare(
            PrepareTerritoryMaintenanceRestoration request) {
        StoredTerritoryMaintenanceRestoration replay = database.territoryMaintenanceRestoration(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            requireReplayPayload(replay, request);
            return toRestoration(replay);
        }
        Instant preparedAt = clock.instant();
        if (!request.nextFullCycleStartsAt().isAfter(preparedAt)) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Restoration must prepay a future full Cycle");
        }
        var policy = database.currentTerritoryMaintenancePolicy(
                request.nextFullCycleStartsAt().toEpochMilli());
        if (policy == null || !policy.policyId().equals(request.policyId())) {
            throw new IllegalStateException(
                    "Territory Maintenance Restoration requires the exact next-Cycle policy");
        }
        long totalDue = request.quote().totalDue().minorUnits();
        long restorationFee = policy.restorationFeeMinorUnits();
        if (totalDue < restorationFee) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Restoration total cannot be below its policy fee");
        }
        Instant expectedCooldown = preparedAt.plus(
                Duration.ofMillis(policy.restorationCooldownMillis()));
        if (!request.quote().cooldownEndsAt().equals(expectedCooldown)) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Restoration cooldown must match its policy");
        }
        long prepayment = Math.subtractExact(totalDue, restorationFee);
        return toRestoration(database.prepareTerritoryMaintenanceRestoration(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.nationId().value(),
                request.ftbTeamId(),
                request.actorPlayerId(),
                request.dimensionId(),
                request.chunkX(),
                request.chunkZ(),
                request.policyId(),
                request.nextFullCycleStartsAt().toEpochMilli(),
                prepayment,
                restorationFee,
                totalDue,
                request.quote().cooldownEndsAt().toEpochMilli(),
                policy.destructionBasisPoints(),
                request.reason(),
                preparedAt.toEpochMilli()));
    }

    public Optional<TerritoryMaintenanceRestoration> find(
            ServiceIdentity serviceIdentity, String requestId) {
        if (serviceIdentity == null || requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Restoration lookup is invalid");
        }
        return Optional.ofNullable(database.territoryMaintenanceRestoration(
                        serviceIdentity.value(), requestId))
                .map(TerritoryMaintenanceRestorationRegistry::toRestoration);
    }

    public TerritoryMaintenanceRestoration confirm(
            ConfirmTerritoryMaintenanceRestoration request) {
        StoredTerritoryMaintenanceRestoration stored =
                database.territoryMaintenanceRestoration(request.restorationId());
        if (stored == null) {
            throw new IllegalArgumentException(
                    "Unknown Territory Maintenance Restoration " + request.restorationId());
        }
        if (!stored.serviceIdentity().equals(request.serviceIdentity().value())) {
            throw new SecurityException(
                    "Territory Maintenance Restoration service identity does not match");
        }
        if (stored.state().equals(TerritoryMaintenanceRestorationState.CIVIC_COMMITTED.name())) {
            requireConfirmationEvidence(stored, request);
            return toRestoration(stored);
        }
        return toRestoration(database.confirmTerritoryMaintenanceRestoration(
                request.restorationId(),
                request.serviceIdentity().value(),
                request.reservationId(),
                request.publicFundPaymentId(),
                request.destructionOperationId(),
                clock.millis()));
    }

    private static void requireReplayPayload(
            StoredTerritoryMaintenanceRestoration stored,
            PrepareTerritoryMaintenanceRestoration request) {
        if (!stored.nationId().equals(request.nationId().value())
                || !stored.ftbTeamId().equals(request.ftbTeamId())
                || !stored.actorPlayerId().equals(request.actorPlayerId())
                || !stored.dimensionId().equals(request.dimensionId())
                || stored.chunkX() != request.chunkX()
                || stored.chunkZ() != request.chunkZ()
                || !stored.policyId().equals(request.policyId())
                || stored.nextFullCycleStartsAtEpochMillis()
                        != request.nextFullCycleStartsAt().toEpochMilli()
                || stored.totalDueMinorUnits() != request.quote().totalDue().minorUnits()
                || stored.cooldownEndsAtEpochMillis()
                        != request.quote().cooldownEndsAt().toEpochMilli()
                || !stored.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(
                    request.serviceIdentity(), request.requestId());
        }
    }

    private static void requireConfirmationEvidence(
            StoredTerritoryMaintenanceRestoration stored,
            ConfirmTerritoryMaintenanceRestoration request) {
        if (!java.util.Objects.equals(request.reservationId(), stored.reservationId())
                || !java.util.Objects.equals(
                        request.publicFundPaymentId(), stored.publicFundPaymentId())
                || !java.util.Objects.equals(
                        request.destructionOperationId(), stored.destructionOperationId())) {
            throw new IllegalStateException(
                    "Territory Maintenance Restoration confirmation evidence changed");
        }
    }

    private static TerritoryMaintenanceRestoration toRestoration(
            StoredTerritoryMaintenanceRestoration stored) {
        return new TerritoryMaintenanceRestoration(
                stored.restorationId(),
                new ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                new NationId(stored.nationId()),
                stored.ftbTeamId(),
                stored.actorPlayerId(),
                stored.dimensionId(),
                stored.chunkX(),
                stored.chunkZ(),
                stored.sourceSuspendedAssessmentId(),
                stored.policyId(),
                Instant.ofEpochMilli(stored.nextFullCycleStartsAtEpochMillis()),
                MoneyAmount.ofMinorUnits(stored.nextCyclePrepaymentMinorUnits()),
                MoneyAmount.ofMinorUnits(stored.restorationFeeMinorUnits()),
                MoneyAmount.ofMinorUnits(stored.totalDueMinorUnits()),
                MoneyAmount.ofMinorUnits(stored.remainingNextCycleCreditMinorUnits()),
                Instant.ofEpochMilli(stored.cooldownEndsAtEpochMillis()),
                stored.destructionBasisPoints(),
                TerritoryMaintenanceRestorationState.valueOf(stored.state()),
                Optional.ofNullable(stored.reservationId()),
                Optional.ofNullable(stored.publicFundPaymentId()),
                Optional.ofNullable(stored.destructionOperationId()),
                stored.reason(),
                Instant.ofEpochMilli(stored.preparedAtEpochMillis()),
                Optional.ofNullable(stored.committedAtEpochMillis()).map(Instant::ofEpochMilli));
    }
}
