package org.civiceconomy.nation;

import java.util.Optional;
import java.util.UUID;

public interface NationProvider {
    Optional<NationFacts> find(NationId nationId);

    Optional<NationFacts> findForCitizen(UUID playerId);
}
