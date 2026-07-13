package org.civiceconomy.nation;

import java.util.Optional;
import java.util.UUID;

public final class FtbTeamsNationProvider implements NationProvider {
    private final NationRegistry registry;
    private final NationTeamDirectory teams;

    public FtbTeamsNationProvider(NationRegistry registry, NationTeamDirectory teams) {
        this.registry = registry;
        this.teams = teams;
    }

    @Override
    public Optional<NationFacts> find(NationId nationId) {
        return registry.find(nationId).map(this::facts);
    }

    @Override
    public Optional<NationFacts> findForCitizen(UUID playerId) {
        return teams.findEffectiveTeamForPlayer(playerId)
                .flatMap(team -> registry.findByFtbTeam(team.teamId()).map(registered -> facts(registered, team)));
    }

    private NationFacts facts(RegisteredNation registered) {
        NationTeam team = teams.find(registered.ftbTeamId())
                .orElseThrow(() -> new NationFactsUnavailableException(registered.nationId()));
        return facts(registered, team);
    }

    private static NationFacts facts(RegisteredNation registered, NationTeam team) {
        return new NationFacts(registered.nationId(), team.headId(), team.citizens());
    }
}
