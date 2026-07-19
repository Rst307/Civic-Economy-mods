package org.civiceconomy.nation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredNation;

public final class NationRegistry {
    private final CivicDatabase database;
    private final NationTeamDirectory teams;

    public NationRegistry(CivicDatabase database, NationTeamDirectory teams) {
        this.database = database;
        this.teams = teams;
    }

    public RegisteredNation register(RegisterNation request) {
        StoredNation replay = database.nationRegistration(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            if (!replay.ftbTeamId().equals(request.ftbTeamId())) {
                throw new IdempotencyConflictException(request.serviceIdentity(), request.requestId());
            }
            return toRegisteredNation(replay);
        }
        if (teams.find(request.ftbTeamId()).isEmpty()) {
            throw new UnknownFtbTeamException(request.ftbTeamId());
        }
        StoredNation bound = database.nationByFtbTeam(request.ftbTeamId());
        if (bound != null) {
            throw new FtbTeamAlreadyBoundException(request.ftbTeamId(), new NationId(bound.nationId()));
        }
        return toRegisteredNation(database.registerNation(
                NationId.create().value(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.ftbTeamId(),
                System.currentTimeMillis()));
    }

    public Optional<RegisteredNation> find(NationId nationId) {
        return Optional.ofNullable(database.nation(nationId.value())).map(NationRegistry::toRegisteredNation);
    }

    public Optional<RegisteredNation> findByFtbTeam(UUID ftbTeamId) {
        return Optional.ofNullable(database.nationByFtbTeam(ftbTeamId)).map(NationRegistry::toRegisteredNation);
    }

    public List<RegisteredNation> registeredNations() {
        return database.registeredNations().stream()
                .map(NationRegistry::toRegisteredNation)
                .toList();
    }

    private static RegisteredNation toRegisteredNation(StoredNation stored) {
        return new RegisteredNation(
                new NationId(stored.nationId()), stored.ftbTeamId(), stored.registeredAtEpochMillis());
    }
}
