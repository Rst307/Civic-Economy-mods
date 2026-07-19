package org.civiceconomy.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.civiceconomy.nation.CitizenshipCorrectionGraceRegistry;
import org.civiceconomy.nation.CitizenshipRegistry;
import org.civiceconomy.nation.FtbTeamsNationProvider;
import org.civiceconomy.nation.GrantNationFiscalPermission;
import org.civiceconomy.nation.JoinCitizenship;
import org.civiceconomy.nation.NationFiscalAuthorityRegistry;
import org.civiceconomy.nation.NationFiscalPermission;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.nation.NationProvider;
import org.civiceconomy.nation.NationRegistry;
import org.civiceconomy.nation.NationTeam;
import org.civiceconomy.nation.NationTeamDirectory;
import org.civiceconomy.nation.RegisterNation;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NationBudgetInspectionTest {
    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID VIEWER =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_HEAD =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID UNAUTHORIZED_CITIZEN =
            UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID VIEWER_TEAM =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TEAM =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir Path temporaryDirectory;

    @Test
    void authorizedCitizenListsOnlyOwnNationalTreasuryBudgetsInExpiryOrder() {
        try (CivicDatabase database = database()) {
            Fixtures fixtures = fixtures(database);
            fixtures.authorities().grant(new GrantNationFiscalPermission(
                    new ServiceIdentity("civiceconomy-governance"),
                    "grant-budget-inspection",
                    fixtures.nationOne(),
                    VIEWER,
                    VIEWER,
                    NationFiscalPermission.VIEW_ACCOUNT,
                    "Treasury viewer may inspect Budgets"));
            Budget later = createBudget(
                    database,
                    UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
                    "later",
                    fixtures.nationOne(),
                    500L,
                    Instant.parse("2026-07-21T00:00:00Z"));
            Budget earlier = createBudget(
                    database,
                    UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
                    "earlier",
                    fixtures.nationOne(),
                    300L,
                    Instant.parse("2026-07-20T00:00:00Z"));
            createBudget(
                    database,
                    UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"),
                    "foreign",
                    fixtures.nationTwo(),
                    700L,
                    Instant.parse("2026-07-19T00:00:00Z"));

            NationBudgetInspection inspection = new NationBudgetInspection(
                    database, fixtures.provider(), fixtures.authorities());

            assertEquals(List.of(earlier, later), inspection.list(VIEWER));
        }
    }

    @Test
    void statusRejectsForeignAndUnknownIdsWhileListRequiresViewAuthority() {
        try (CivicDatabase database = database()) {
            Fixtures fixtures = fixtures(database);
            fixtures.authorities().grant(new GrantNationFiscalPermission(
                    new ServiceIdentity("civiceconomy-governance"),
                    "grant-budget-status",
                    fixtures.nationOne(),
                    VIEWER,
                    VIEWER,
                    NationFiscalPermission.VIEW_ACCOUNT,
                    "Treasury viewer may inspect Budget status"));
            Budget own = createBudget(
                    database,
                    UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111"),
                    "own-status",
                    fixtures.nationOne(),
                    300L,
                    Instant.parse("2026-07-20T00:00:00Z"));
            Budget foreign = createBudget(
                    database,
                    UUID.fromString("bbbbbbbb-2222-2222-2222-222222222222"),
                    "foreign-status",
                    fixtures.nationTwo(),
                    700L,
                    Instant.parse("2026-07-21T00:00:00Z"));
            NationBudgetInspection inspection = new NationBudgetInspection(
                    database, fixtures.provider(), fixtures.authorities());

            assertEquals(own, inspection.status(VIEWER, own.budgetId()));
            assertThrows(
                    SecurityException.class,
                    () -> inspection.status(VIEWER, foreign.budgetId()));
            assertThrows(
                    SecurityException.class,
                    () -> inspection.status(
                            VIEWER,
                            UUID.fromString("ffffffff-1111-1111-1111-111111111111")));
            assertThrows(
                    SecurityException.class,
                    () -> inspection.list(UNAUTHORIZED_CITIZEN));
        }
    }

    private static Budget createBudget(
            CivicDatabase database,
            UUID budgetId,
            String requestId,
            NationId nationId,
            long amountMinorUnits,
            Instant expiresAt) {
        return FiscalLedger.toBudget(database.createBudget(
                budgetId,
                "civiceconomy-budget",
                requestId,
                "nation:" + nationId.value() + ":treasury",
                amountMinorUnits,
                "PUBLIC_WORKS:" + requestId.toUpperCase(),
                "Inspection " + requestId,
                expiresAt.toEpochMilli()));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("nation-budget-inspection.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }

    private static Fixtures fixtures(CivicDatabase database) {
        NationTeamDirectory teams = new NationTeamDirectory() {
            @Override
            public Optional<NationTeam> find(UUID teamId) {
                if (VIEWER_TEAM.equals(teamId)) {
                    return Optional.of(new NationTeam(
                            VIEWER_TEAM,
                            VIEWER,
                            Set.of(VIEWER, UNAUTHORIZED_CITIZEN)));
                }
                if (OTHER_TEAM.equals(teamId)) {
                    return Optional.of(new NationTeam(
                            OTHER_TEAM, OTHER_HEAD, Set.of(OTHER_HEAD)));
                }
                return Optional.empty();
            }

            @Override
            public Optional<NationTeam> findEffectiveTeamForPlayer(UUID playerId) {
                if (Set.of(VIEWER, UNAUTHORIZED_CITIZEN).contains(playerId)) {
                    return find(VIEWER_TEAM);
                }
                if (OTHER_HEAD.equals(playerId)) {
                    return find(OTHER_TEAM);
                }
                return Optional.empty();
            }
        };
        NationRegistry nations = new NationRegistry(database, teams);
        NationId nationOne = nations.register(new RegisterNation(
                new ServiceIdentity("civiceconomy"),
                "register-budget-inspection-one",
                VIEWER_TEAM)).nationId();
        NationId nationTwo = nations.register(new RegisterNation(
                new ServiceIdentity("civiceconomy"),
                "register-budget-inspection-two",
                OTHER_TEAM)).nationId();
        CitizenshipRegistry citizenships = new CitizenshipRegistry(
                database, Duration.ZERO, CLOCK);
        citizenships.join(new JoinCitizenship(
                new ServiceIdentity("civiceconomy"),
                "join-budget-inspection-viewer",
                VIEWER,
                nationOne));
        citizenships.join(new JoinCitizenship(
                new ServiceIdentity("civiceconomy"),
                "join-budget-inspection-unauthorized",
                UNAUTHORIZED_CITIZEN,
                nationOne));
        citizenships.join(new JoinCitizenship(
                new ServiceIdentity("civiceconomy"),
                "join-budget-inspection-other",
                OTHER_HEAD,
                nationTwo));
        NationProvider provider = new FtbTeamsNationProvider(
                nations,
                citizenships,
                new CitizenshipCorrectionGraceRegistry(database, CLOCK),
                teams);
        return new Fixtures(
                nationOne,
                nationTwo,
                provider,
                new NationFiscalAuthorityRegistry(database, provider, CLOCK));
    }

    private record Fixtures(
            NationId nationOne,
            NationId nationTwo,
            NationProvider provider,
            NationFiscalAuthorityRegistry authorities) {}
}
