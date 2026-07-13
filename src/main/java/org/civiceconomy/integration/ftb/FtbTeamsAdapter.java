package org.civiceconomy.integration.ftb;

import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamManager;
import java.util.Optional;
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
