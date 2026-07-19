package org.civiceconomy.nation;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.persistence.CivicDatabase;

public final class NationApplicationExpiryProcessor {
    static final ServiceIdentity SERVICE =
            new ServiceIdentity("civiceconomy-nation-application-expiry");
    private static final String REASON = "Nation Application reached its expiry deadline";
    private static final NationTeamDirectory NO_TEAM_LOOKUPS = new NationTeamDirectory() {
        @Override
        public Optional<NationTeam> find(UUID teamId) {
            return Optional.empty();
        }

        @Override
        public Optional<NationTeam> findEffectiveTeamForPlayer(UUID playerId) {
            return Optional.empty();
        }
    };

    private final CivicDatabase database;
    private final Clock clock;
    public NationApplicationExpiryProcessor(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException(
                    "Nation Application expiry dependencies are required");
        }
        this.database = database;
        this.clock = clock;
    }

    public List<NationApplication> expireDue() {
        Instant asOf = clock.instant();
        var policy = new CandidateOnlineEvidencePolicyRegistry(database, clock).current(asOf);
        if (policy.isEmpty()) {
            return List.of();
        }
        NationApplicationRegistry registry =
                new NationApplicationRegistry(database, NO_TEAM_LOOKUPS, clock);
        return registry.pendingExpiringAtOrBefore(asOf).stream()
                .map(application -> registry.expire(new ExpireNationApplication(
                        SERVICE,
                        requestId(application.applicationId()),
                        application.applicationId(),
                        policy.orElseThrow().policy().observationWindow(),
                        REASON)))
                .toList();
    }

    static String requestId(NationApplicationId applicationId) {
        return "automatic-expiry:" + applicationId.value();
    }
}
