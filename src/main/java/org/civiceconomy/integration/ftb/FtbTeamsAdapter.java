package org.civiceconomy.integration.ftb;

import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamManager;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public final class FtbTeamsAdapter {
    private final TeamManager manager;

    private FtbTeamsAdapter(TeamManager manager) {
        this.manager = manager;
    }

    public static FtbTeamsAdapter live() {
        FTBTeamsAPI.API api = FTBTeamsAPI.api();
        if (api == null || !api.isManagerLoaded()) {
            throw new FtbIntegrationUnavailableException("FTB Teams server manager is not loaded");
        }
        return new FtbTeamsAdapter(api.getManager());
    }

    public Optional<FtbTeamFacts> find(UUID teamId) {
        return manager.getTeamByID(teamId).filter(Team::isValid).map(FtbTeamsAdapter::facts);
    }

    public Optional<FtbTeamFacts> findEffectiveTeamForPlayer(UUID playerId) {
        return manager.getTeamForPlayerID(playerId).filter(Team::isValid).map(FtbTeamsAdapter::facts);
    }

    public Optional<FtbTeamFacts> findPersonalTeamForPlayer(UUID playerId) {
        return manager.getPlayerTeamForPlayerID(playerId).filter(Team::isValid).map(FtbTeamsAdapter::facts);
    }

    public Optional<FtbTeamFacts> findOwnedNonPlayerTeam(UUID playerId) {
        List<FtbTeamFacts> owned = manager.getTeams().stream()
                .filter(Team::isValid)
                .filter(team -> !team.isPlayerTeam())
                .filter(team -> playerId.equals(team.getOwner()))
                .map(FtbTeamsAdapter::facts)
                .toList();
        if (owned.size() > 1) {
            throw new FtbIntegrationUnavailableException(
                    "Player owns multiple non-player FTB Teams: " + playerId);
        }
        return owned.stream().findFirst();
    }

    private static FtbTeamFacts facts(Team team) {
        return new FtbTeamFacts(
                team.getId(),
                team.getTeamId(),
                team.getOwner(),
                team.getMembers(),
                team.isPlayerTeam(),
                team.isPartyTeam(),
                team.isServerTeam());
    }
}
