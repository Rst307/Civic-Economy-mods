package org.civiceconomy.nation;

import java.util.Optional;
import java.util.UUID;

public final class FtbTeamsNationProvider implements NationProvider {
    private final NationRegistry registry;
    private final CitizenshipRegistry citizenships;
    private final CitizenshipCorrectionGraceRegistry corrections;
    private final NationTeamDirectory teams;

    public FtbTeamsNationProvider(
            NationRegistry registry,
            CitizenshipRegistry citizenships,
            CitizenshipCorrectionGraceRegistry corrections,
            NationTeamDirectory teams) {
        if (registry == null || citizenships == null || corrections == null || teams == null) {
            throw new IllegalArgumentException("Nation Provider dependencies cannot be null");
        }
        this.registry = registry;
        this.citizenships = citizenships;
        this.corrections = corrections;
        this.teams = teams;
    }

    @Override
    public Optional<NationFacts> find(NationId nationId) {
        return registry.find(nationId).map(this::facts);
    }

    @Override
    public Optional<NationFacts> findForCitizen(UUID playerId) {
        return citizenships.current(playerId)
                .flatMap(citizenship -> registry.find(citizenship.nationId()))
                .flatMap(registered -> teams.find(registered.ftbTeamId())
                        .filter(team -> team.citizens().contains(playerId))
                        .filter(team -> corrections.activeForPlayer(playerId).isEmpty())
                        .map(team -> facts(registered, team)));
    }

    private NationFacts facts(RegisteredNation registered) {
        NationTeam team = teams.find(registered.ftbTeamId())
                .orElseThrow(() -> new NationFactsUnavailableException(registered.nationId()));
        return facts(registered, team);
    }

    private NationFacts facts(RegisteredNation registered, NationTeam team) {
        java.util.Set<UUID> formalCitizens = citizenships.currentForNation(registered.nationId()).stream()
                .filter(citizenship -> team.citizens().contains(citizenship.playerId()))
                .filter(citizenship -> corrections.activeForPlayer(citizenship.playerId()).isEmpty())
                .map(Citizenship::playerId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new NationFacts(registered.nationId(), team.headId(), formalCitizens);
    }
}
