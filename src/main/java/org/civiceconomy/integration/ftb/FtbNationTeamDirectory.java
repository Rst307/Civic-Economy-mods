package org.civiceconomy.integration.ftb;

import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.nation.NationTeam;
import org.civiceconomy.nation.NationTeamDirectory;

public final class FtbNationTeamDirectory implements NationTeamDirectory {
    private final FtbTeamsAdapter teams;

    private FtbNationTeamDirectory(FtbTeamsAdapter teams) {
        this.teams = teams;
    }

    public static FtbNationTeamDirectory live() {
        return new FtbNationTeamDirectory(FtbTeamsAdapter.live());
    }

    @Override
    public Optional<NationTeam> find(UUID teamId) {
        return teams.find(teamId).map(FtbNationTeamDirectory::toNationTeam);
    }

    @Override
    public Optional<NationTeam> findEffectiveTeamForPlayer(UUID playerId) {
        return teams.findEffectiveTeamForPlayer(playerId).map(FtbNationTeamDirectory::toNationTeam);
    }

    public Optional<NationTeam> findOwnedTeamForPlayer(UUID playerId) {
        return teams.findOwnedNonPlayerTeam(playerId).map(FtbNationTeamDirectory::toNationTeam);
    }

    private static NationTeam toNationTeam(FtbTeamFacts facts) {
        return new NationTeam(facts.teamId(), facts.ownerId(), facts.members());
    }
}
