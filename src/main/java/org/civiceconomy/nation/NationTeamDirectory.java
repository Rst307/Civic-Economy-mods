package org.civiceconomy.nation;

import java.util.Optional;
import java.util.UUID;

public interface NationTeamDirectory {
    Optional<NationTeam> find(UUID teamId);

    Optional<NationTeam> findEffectiveTeamForPlayer(UUID playerId);
}
