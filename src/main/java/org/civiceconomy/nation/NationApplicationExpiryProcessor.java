package org.civiceconomy.nation;

import java.time.Clock;
import java.time.Duration;
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
    private final Duration observationWindow;

    public NationApplicationExpiryProcessor(
            CivicDatabase database, Clock clock, Duration observationWindow) {
        if (database == null || clock == null || observationWindow == null
                || observationWindow.isZero() || observationWindow.isNegative()) {
            throw new IllegalArgumentException(
                    "Nation Application expiry dependencies and positive window are required");
        }
        this.database = database;
        this.clock = clock;
        this.observationWindow = observationWindow;
    }

    public List<NationApplication> expireDue() {
        NationApplicationRegistry registry =
                new NationApplicationRegistry(database, NO_TEAM_LOOKUPS, clock);
        return registry.pendingExpiringAtOrBefore(clock.instant()).stream()
                .map(application -> registry.expire(new ExpireNationApplication(
                        SERVICE,
                        requestId(application.applicationId()),
                        application.applicationId(),
                        observationWindow,
                        REASON)))
                .toList();
    }

    static String requestId(NationApplicationId applicationId) {
        return "automatic-expiry:" + applicationId.value();
    }
}
