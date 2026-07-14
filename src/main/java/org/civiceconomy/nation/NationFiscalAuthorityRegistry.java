package org.civiceconomy.nation;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredNationFiscalPermissionGrant;

public final class NationFiscalAuthorityRegistry {
    private final CivicDatabase database;
    private final NationProvider nations;
    private final Clock clock;

    public NationFiscalAuthorityRegistry(
            CivicDatabase database, NationProvider nations, Clock clock) {
        if (database == null || nations == null || clock == null) {
            throw new IllegalArgumentException("Nation Fiscal Authority dependencies cannot be null");
        }
        this.database = database;
        this.nations = nations;
        this.clock = clock;
    }

    public NationFiscalPermissionGrant grant(GrantNationFiscalPermission request) {
        StoredNationFiscalPermissionGrant replay = database.nationFiscalPermissionGrant(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            requirePayload(replay, request);
            return toGrant(replay);
        }
        NationFacts actorNation = nations.findForCitizen(request.actorPlayerId())
                .orElseThrow(() -> new SecurityException(
                        "Fiscal role actor has no effective Citizenship"));
        if (!actorNation.nationId().equals(request.nationId())
                || !actorNation.headId().equals(request.actorPlayerId())) {
            throw new SecurityException(
                    "Only the effective head of the exact Nation can grant fiscal permissions");
        }
        if (!actorNation.citizens().contains(request.playerId())) {
            throw new SecurityException(
                    "Fiscal permissions can only be granted to an effective Citizen of the Nation");
        }
        StoredNationFiscalPermissionGrant existing = database.activeNationFiscalPermissionGrant(
                request.nationId().value(), request.playerId(), request.permission().name());
        if (existing != null) {
            throw new IllegalStateException(
                    "Nation Fiscal Permission is already active for this Citizen and Nation");
        }
        return toGrant(database.grantNationFiscalPermission(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.nationId().value(),
                request.actorPlayerId(),
                request.playerId(),
                request.permission().name(),
                request.reason(),
                clock.millis()));
    }

    public List<NationFiscalPermissionGrant> activeGrants(NationId nationId, UUID playerId) {
        if (nationId == null || playerId == null) {
            throw new IllegalArgumentException("Nation Fiscal Authority query cannot contain null values");
        }
        return database.activeNationFiscalPermissionGrants(nationId.value(), playerId).stream()
                .map(NationFiscalAuthorityRegistry::toGrant)
                .toList();
    }

    public List<NationFiscalPermissionGrant> activeGrants(NationId nationId) {
        if (nationId == null) {
            throw new IllegalArgumentException("Nation Fiscal Authority Nation cannot be null");
        }
        return database.activeNationFiscalPermissionGrants(nationId.value()).stream()
                .map(NationFiscalAuthorityRegistry::toGrant)
                .toList();
    }

    public NationFiscalPermissionRevocation revoke(RevokeNationFiscalPermission request) {
        var replay = database.nationFiscalPermissionRevocation(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            if (!replay.grantId().equals(request.grantId())
                    || !replay.nationId().equals(request.nationId().value())
                    || !replay.actorPlayerId().equals(request.actorPlayerId())
                    || !replay.reason().equals(request.reason())) {
                throw new IdempotencyConflictException(
                        request.serviceIdentity(), request.requestId());
            }
            return toRevocation(replay);
        }
        NationFacts actorNation = nations.findForCitizen(request.actorPlayerId())
                .orElseThrow(() -> new SecurityException(
                        "Fiscal role actor has no effective Citizenship"));
        if (!actorNation.nationId().equals(request.nationId())
                || !actorNation.headId().equals(request.actorPlayerId())) {
            throw new SecurityException(
                    "Only the effective head of the exact Nation can revoke fiscal permissions");
        }
        StoredNationFiscalPermissionGrant grant = database.nationFiscalPermissionGrant(request.grantId());
        if (grant == null || !grant.nationId().equals(request.nationId().value())) {
            throw new IllegalArgumentException(
                    "Unknown Nation Fiscal Permission grant " + request.grantId());
        }
        if (database.nationFiscalPermissionRevocation(request.grantId()) != null) {
            throw new IllegalStateException(
                    "Nation Fiscal Permission grant is already revoked " + request.grantId());
        }
        return toRevocation(database.revokeNationFiscalPermission(
                UUID.randomUUID(),
                request.grantId(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.nationId().value(),
                request.actorPlayerId(),
                request.reason(),
                clock.millis()));
    }

    public Optional<NationFiscalPermissionRevocation> revocation(UUID grantId) {
        if (grantId == null) {
            throw new IllegalArgumentException("Nation Fiscal Permission grant ID cannot be null");
        }
        return Optional.ofNullable(database.nationFiscalPermissionRevocation(grantId))
                .map(NationFiscalAuthorityRegistry::toRevocation);
    }

    public Set<NationFiscalPermission> effectivePermissions(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Nation Fiscal Authority player cannot be null");
        }
        return nations.findForCitizen(playerId)
                .map(facts -> activeGrants(facts.nationId(), playerId).stream()
                        .map(NationFiscalPermissionGrant::permission)
                        .collect(Collectors.toUnmodifiableSet()))
                .orElseGet(Set::of);
    }

    public void require(
            NationId nationId, UUID playerId, NationFiscalPermission permission) {
        if (nationId == null || playerId == null || permission == null) {
            throw new IllegalArgumentException("Nation Fiscal Authority requirement cannot be null");
        }
        NationFacts facts = nations.findForCitizen(playerId)
                .orElseThrow(() -> new SecurityException(
                        "Fiscal actor has no effective Citizenship"));
        if (!facts.nationId().equals(nationId)) {
            throw new SecurityException(
                    "Fiscal actor does not belong to the exact Nation");
        }
        boolean granted = activeGrants(nationId, playerId).stream()
                .anyMatch(grant -> grant.permission() == permission);
        if (!granted) {
            throw new SecurityException(
                    "Fiscal actor lacks " + permission + " for the exact Nation");
        }
    }

    private static void requirePayload(
            StoredNationFiscalPermissionGrant stored, GrantNationFiscalPermission request) {
        if (!stored.nationId().equals(request.nationId().value())
                || !stored.actorPlayerId().equals(request.actorPlayerId())
                || !stored.playerId().equals(request.playerId())
                || !stored.permission().equals(request.permission().name())
                || !stored.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(
                    request.serviceIdentity(), request.requestId());
        }
    }

    private static NationFiscalPermissionGrant toGrant(
            StoredNationFiscalPermissionGrant stored) {
        return new NationFiscalPermissionGrant(
                stored.grantId(),
                new NationId(stored.nationId()),
                stored.playerId(),
                NationFiscalPermission.valueOf(stored.permission()),
                stored.actorPlayerId(),
                stored.reason(),
                Instant.ofEpochMilli(stored.grantedAtEpochMillis()));
    }

    private static NationFiscalPermissionRevocation toRevocation(
            org.civiceconomy.persistence.StoredNationFiscalPermissionRevocation stored) {
        return new NationFiscalPermissionRevocation(
                stored.revocationId(),
                stored.grantId(),
                new NationId(stored.nationId()),
                stored.actorPlayerId(),
                stored.reason(),
                Instant.ofEpochMilli(stored.revokedAtEpochMillis()));
    }
}
