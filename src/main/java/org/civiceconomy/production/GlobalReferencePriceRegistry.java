package org.civiceconomy.production;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredGlobalReferencePrice;

public final class GlobalReferencePriceRegistry {
    private final CivicDatabase database;
    private final Clock clock;

    public GlobalReferencePriceRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException("Global Reference Price dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public GlobalReferencePriceVersion schedule(ScheduleGlobalReferencePrice request) {
        StoredGlobalReferencePrice replay = database.globalReferencePrice(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            requirePayload(replay, request);
            return toVersion(replay);
        }
        if (request.effectiveAt().toEpochMilli() <= clock.millis()) {
            throw new IllegalArgumentException("Global Reference Price must take effect in the future");
        }
        return toVersion(database.scheduleGlobalReferencePrice(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.actorIdentity(),
                request.itemId(),
                request.componentFingerprint(),
                request.unitPriceMinorUnits(),
                request.effectiveAt().toEpochMilli(),
                request.reason(),
                clock.millis()));
    }

    public Optional<GlobalReferencePriceVersion> current(
            String itemId, String componentFingerprint, Instant asOf) {
        if (itemId == null || itemId.isBlank()
                || componentFingerprint == null || componentFingerprint.isBlank()
                || asOf == null) {
            throw new IllegalArgumentException("Global Reference Price lookup is invalid");
        }
        return Optional.ofNullable(database.currentGlobalReferencePrice(
                        itemId, componentFingerprint, asOf.toEpochMilli()))
                .map(GlobalReferencePriceRegistry::toVersion);
    }

    public Optional<GlobalReferencePriceVersion> current(
            ProductionStack stack, Instant asOf) {
        if (stack == null) {
            throw new IllegalArgumentException("Global Reference Price stack cannot be null");
        }
        return current(stack.itemId(), stack.componentFingerprint(), asOf);
    }

    private static void requirePayload(
            StoredGlobalReferencePrice stored, ScheduleGlobalReferencePrice request) {
        if (!stored.actorIdentity().equals(request.actorIdentity())
                || !stored.itemId().equals(request.itemId())
                || !stored.componentFingerprint().equals(request.componentFingerprint())
                || stored.unitPriceMinorUnits() != request.unitPriceMinorUnits()
                || stored.effectiveAtEpochMillis() != request.effectiveAt().toEpochMilli()
                || !stored.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(request.serviceIdentity(), request.requestId());
        }
    }

    private static GlobalReferencePriceVersion toVersion(StoredGlobalReferencePrice stored) {
        return new GlobalReferencePriceVersion(
                stored.priceId(),
                stored.itemId(),
                stored.componentFingerprint(),
                stored.unitPriceMinorUnits(),
                Instant.ofEpochMilli(stored.effectiveAtEpochMillis()),
                stored.actorIdentity(),
                stored.reason(),
                Instant.ofEpochMilli(stored.recordedAtEpochMillis()));
    }
}
