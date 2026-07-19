package org.civiceconomy.production;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredFacilityClaim;
import org.civiceconomy.persistence.StoredRegisteredFacility;
import org.civiceconomy.territory.TerritoryClaimPosition;

public final class RegisteredFacilityRegistry {
    private static final Comparator<TerritoryClaimPosition> CLAIM_ORDER = Comparator
            .comparing(TerritoryClaimPosition::dimensionId)
            .thenComparingInt(TerritoryClaimPosition::chunkX)
            .thenComparingInt(TerritoryClaimPosition::chunkZ);

    private final CivicDatabase database;
    private final RegisteredFacilityTerritoryAuthority territoryAuthority;
    private final Clock clock;
    private final int maxScopeChunks;

    public RegisteredFacilityRegistry(
            CivicDatabase database,
            RegisteredFacilityTerritoryAuthority territoryAuthority,
            Clock clock,
            int maxScopeChunks) {
        if (database == null || territoryAuthority == null || clock == null
                || maxScopeChunks <= 0) {
            throw new IllegalArgumentException("Registered Facility dependencies are invalid");
        }
        this.database = database;
        this.territoryAuthority = territoryAuthority;
        this.clock = clock;
        this.maxScopeChunks = maxScopeChunks;
    }

    public RegisteredFacility register(RegisterFacility request) {
        if (request == null) {
            throw new IllegalArgumentException("Registered Facility request is required");
        }
        List<TerritoryClaimPosition> scope = validateScope(request);
        StoredRegisteredFacility replay = database.registeredFacility(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            return toFacility(persist(request, scope));
        }
        boolean effective = scope.stream().allMatch(claim -> territoryAuthority.isEffective(
                request.nationId(), request.ftbTeamId(), claim));
        if (!effective) {
            throw new SecurityException(
                    "Registered Facility scope is not exact-Nation Effective Territory");
        }
        return toFacility(persist(request, scope));
    }

    private StoredRegisteredFacility persist(
            RegisterFacility request, List<TerritoryClaimPosition> scope) {
        return database.registerFacility(
                request.facilityId(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.nationId().value(),
                request.ftbTeamId(),
                request.core().dimensionId(),
                request.core().blockX(),
                request.core().blockY(),
                request.core().blockZ(),
                request.actorPlayerId(),
                request.reason(),
                clock.millis(),
                toStored(scope));
    }

    public RegisteredFacility facility(UUID facilityId) {
        StoredRegisteredFacility stored = database.registeredFacility(facilityId);
        return stored == null ? null : toFacility(stored);
    }

    public RegisteredFacility facilityAt(FacilityAccountingInterfacePosition position) {
        if (position == null) {
            return null;
        }
        StoredRegisteredFacility stored = database.registeredFacilityAt(
                position.dimensionId(), position.blockX(), position.blockZ());
        return stored == null ? null : toFacility(stored);
    }

    private List<TerritoryClaimPosition> validateScope(RegisterFacility request) {
        Set<TerritoryClaimPosition> unique = new HashSet<>(request.scope());
        if (unique.isEmpty() || unique.size() != request.scope().size()
                || unique.size() > maxScopeChunks
                || unique.stream().anyMatch(claim ->
                        !request.core().dimensionId().equals(claim.dimensionId()))
                || !unique.contains(request.core().claim())
                || !isContiguous(unique)) {
            throw new IllegalArgumentException("Registered Facility scope is invalid");
        }
        return unique.stream().sorted(CLAIM_ORDER).toList();
    }

    private static boolean isContiguous(Set<TerritoryClaimPosition> scope) {
        Set<TerritoryClaimPosition> visited = new HashSet<>();
        ArrayDeque<TerritoryClaimPosition> pending = new ArrayDeque<>();
        pending.add(scope.iterator().next());
        while (!pending.isEmpty()) {
            TerritoryClaimPosition current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            addIfPresent(scope, pending, current, 1, 0);
            addIfPresent(scope, pending, current, -1, 0);
            addIfPresent(scope, pending, current, 0, 1);
            addIfPresent(scope, pending, current, 0, -1);
        }
        return visited.size() == scope.size();
    }

    private static void addIfPresent(
            Set<TerritoryClaimPosition> scope,
            ArrayDeque<TerritoryClaimPosition> pending,
            TerritoryClaimPosition current,
            int deltaX,
            int deltaZ) {
        TerritoryClaimPosition neighbor = new TerritoryClaimPosition(
                current.dimensionId(), current.chunkX() + deltaX, current.chunkZ() + deltaZ);
        if (scope.contains(neighbor)) {
            pending.addLast(neighbor);
        }
    }

    private RegisteredFacility toFacility(StoredRegisteredFacility stored) {
        return new RegisteredFacility(
                stored.facilityId(),
                new ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                new NationId(stored.nationId()),
                stored.ftbTeamId(),
                new FacilityCorePosition(
                        stored.dimensionId(),
                        stored.coreBlockX(),
                        stored.coreBlockY(),
                        stored.coreBlockZ()),
                database.registeredFacilityClaims(stored.facilityId()).stream()
                        .map(claim -> new TerritoryClaimPosition(
                                claim.dimensionId(), claim.chunkX(), claim.chunkZ()))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                stored.actorPlayerId(),
                RegisteredFacilityState.valueOf(stored.state()),
                stored.reason(),
                Instant.ofEpochMilli(stored.registeredAtEpochMillis()));
    }

    private static List<StoredFacilityClaim> toStored(List<TerritoryClaimPosition> scope) {
        return scope.stream()
                .map(claim -> new StoredFacilityClaim(
                        claim.dimensionId(), claim.chunkX(), claim.chunkZ()))
                .toList();
    }
}
