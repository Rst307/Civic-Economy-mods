package org.civiceconomy.territory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.fiscal.FailurePoint;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.fiscal.PaymentCoordinator;
import org.civiceconomy.fiscal.RefundTerritoryClaimPermitPayment;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.fiscal.TransactionState;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredTerritoryClaimPermitCompensation;

public final class TerritoryClaimPermitCompensationCoordinator {
    private final CivicDatabase database;
    private final PaymentCoordinator payments;
    private final TerritoryClaimPermitRegistry permits;
    private final ServiceIdentity serviceIdentity;
    private final Clock clock;

    public TerritoryClaimPermitCompensationCoordinator(
            CivicDatabase database,
            PaymentCoordinator payments,
            TerritoryClaimPermitRegistry permits,
            ServiceIdentity serviceIdentity,
            Clock clock) {
        if (database == null
                || payments == null
                || permits == null
                || serviceIdentity == null
                || clock == null) {
            throw new IllegalArgumentException(
                    "Territory Claim Permit compensation dependencies cannot be null");
        }
        this.database = database;
        this.payments = payments;
        this.permits = permits;
        this.serviceIdentity = serviceIdentity;
        this.clock = clock;
    }

    public TerritoryClaimPermit cancel(CancelTerritoryClaimPermit request) {
        TerritoryClaimPermit permit = permits.find(request.permitId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown Territory Claim Permit " + request.permitId()));
        if (!permit.actorPlayerId().equals(request.actorPlayerId())) {
            throw new SecurityException(
                    "Only the exact Territory Claim Permit actor can cancel it");
        }
        String actorIdentity = "player:" + request.actorPlayerId();
        String refundRequestId = request.requestId() + ":refund";
        StoredTerritoryClaimPermitCompensation compensation =
                database.startTerritoryClaimPermitCompensation(
                        UUID.randomUUID(),
                        request.permitId(),
                        serviceIdentity.value(),
                        request.requestId(),
                        actorIdentity,
                        "CANCEL",
                        request.reason(),
                        refundRequestId,
                        clock.millis());
        requireCancellationPayload(
                compensation, request, actorIdentity, refundRequestId);
        return finish(compensation);
    }

    public List<TerritoryClaimPermit> expireDue() {
        List<TerritoryClaimPermit> expired = new ArrayList<>();
        for (var permit : database.dueTerritoryClaimPermits(clock.millis())) {
            String requestId = "expire:" + permit.permitId();
            String reason = "Territory Claim Permit expired at "
                    + Instant.ofEpochMilli(permit.expiresAtEpochMillis());
            StoredTerritoryClaimPermitCompensation compensation =
                    database.startTerritoryClaimPermitCompensation(
                            UUID.randomUUID(),
                            permit.permitId(),
                            serviceIdentity.value(),
                            requestId,
                            "civiceconomy-expiry",
                            "EXPIRE",
                            reason,
                            requestId + ":refund",
                            clock.millis());
            expired.add(finish(compensation));
        }
        return List.copyOf(expired);
    }

    public List<TerritoryClaimPermit> recoverIncomplete() {
        List<TerritoryClaimPermit> recovered = new ArrayList<>();
        for (StoredTerritoryClaimPermitCompensation compensation
                : database.incompleteTerritoryClaimPermitCompensations()) {
            recovered.add(finish(compensation));
        }
        return List.copyOf(recovered);
    }

    private TerritoryClaimPermit finish(
            StoredTerritoryClaimPermitCompensation compensation) {
        if (compensation.completedAtEpochMillis() != null) {
            return permits.find(compensation.permitId()).orElseThrow();
        }
        var refund = payments.refundTerritoryClaimPermit(
                new RefundTerritoryClaimPermitPayment(
                        serviceIdentity,
                        compensation.refundRequestId(),
                        compensation.permitId(),
                        compensation.reason()),
                FailurePoint.NONE);
        if (refund.state() != TransactionState.CIVIC_COMMITTED) {
            throw new IllegalStateException(
                    "Territory Claim Permit refund did not commit " + refund.transactionId());
        }
        return toPermit(database.completeTerritoryClaimPermitCompensation(
                compensation.compensationId(), clock.millis()));
    }

    private void requireCancellationPayload(
            StoredTerritoryClaimPermitCompensation stored,
            CancelTerritoryClaimPermit request,
            String actorIdentity,
            String refundRequestId) {
        if (!stored.permitId().equals(request.permitId())
                || !stored.serviceIdentity().equals(serviceIdentity.value())
                || !stored.requestId().equals(request.requestId())
                || !stored.actorIdentity().equals(actorIdentity)
                || !stored.kind().equals("CANCEL")
                || !stored.reason().equals(request.reason())
                || !stored.refundRequestId().equals(refundRequestId)) {
            throw new IdempotencyConflictException(serviceIdentity, request.requestId());
        }
    }

    private TerritoryClaimPermit toPermit(
            org.civiceconomy.persistence.StoredTerritoryClaimPermit stored) {
        return permits.find(stored.permitId()).orElseThrow();
    }
}
