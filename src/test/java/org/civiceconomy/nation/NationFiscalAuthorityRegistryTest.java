package org.civiceconomy.nation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NationFiscalAuthorityRegistryTest {
    private static final Instant NOW = Instant.parse("2026-07-14T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ServiceIdentity GOVERNANCE_SERVICE =
            new ServiceIdentity("civiceconomy-governance");
    private static final UUID TEAM_ID =
            UUID.fromString("1dcd8ce2-dab2-49a2-9eb6-b9eebf2c59b7");
    private static final UUID HEAD_ID =
            UUID.fromString("b03b3921-90ec-45c7-80c4-ad56945a94f0");
    private static final UUID CITIZEN_ID =
            UUID.fromString("8f06e167-3a07-4b6f-97e0-ac7251d07573");

    @TempDir
    Path temporaryDirectory;

    @Test
    void nationHeadGrantsOneExactPermissionWithReplayAcrossRestart() {
        NationFiscalPermissionGrant granted;
        NationId nationId;

        try (CivicDatabase database = database()) {
            Fixtures fixtures = fixtures(database);
            nationId = fixtures.nationId();
            NationFiscalAuthorityRegistry authorities = fixtures.authorities();
            GrantNationFiscalPermission request = new GrantNationFiscalPermission(
                    GOVERNANCE_SERVICE,
                    "grant-budget-approval",
                    nationId,
                    HEAD_ID,
                    CITIZEN_ID,
                    NationFiscalPermission.APPROVE_BUDGET,
                    "Citizen appointed to approve budgets");

            granted = authorities.grant(request);

            assertEquals(granted, authorities.grant(request));
            assertEquals(
                    Set.of(NationFiscalPermission.APPROVE_BUDGET),
                    authorities.effectivePermissions(CITIZEN_ID));
        }

        try (CivicDatabase reopened = database()) {
            Fixtures fixtures = fixtures(reopened);

            assertEquals(
                    granted,
                    fixtures.authorities().activeGrants(nationId, CITIZEN_ID).getFirst());
            assertEquals(
                    Set.of(NationFiscalPermission.APPROVE_BUDGET),
                    fixtures.authorities().effectivePermissions(CITIZEN_ID));
        }
    }

    @Test
    void nationHeadRevokesPermissionWithoutDeletingGrantProvenance() {
        NationFiscalPermissionGrant granted;
        NationFiscalPermissionRevocation revoked;
        NationId nationId;

        try (CivicDatabase database = database()) {
            Fixtures fixtures = fixtures(database);
            nationId = fixtures.nationId();
            granted = fixtures.authorities().grant(new GrantNationFiscalPermission(
                    GOVERNANCE_SERVICE,
                    "grant-ledger-view",
                    nationId,
                    HEAD_ID,
                    CITIZEN_ID,
                    NationFiscalPermission.VIEW_LEDGER,
                    "Citizen appointed as fiscal auditor"));
            RevokeNationFiscalPermission request = new RevokeNationFiscalPermission(
                    GOVERNANCE_SERVICE,
                    "revoke-ledger-view",
                    nationId,
                    HEAD_ID,
                    granted.grantId(),
                    "Audit appointment ended");

            revoked = fixtures.authorities().revoke(request);

            assertEquals(revoked, fixtures.authorities().revoke(request));
            assertEquals(Set.of(), fixtures.authorities().effectivePermissions(CITIZEN_ID));
            assertEquals(Set.of(), Set.copyOf(fixtures.authorities().activeGrants(nationId, CITIZEN_ID)));
        }

        try (CivicDatabase reopened = database()) {
            Fixtures fixtures = fixtures(reopened);

            assertEquals(
                    revoked,
                    fixtures.authorities().revocation(granted.grantId()).orElseThrow());
            assertEquals(Set.of(), fixtures.authorities().effectivePermissions(CITIZEN_ID));
        }
    }

    @Test
    void differentRequestCannotAliasAnExistingActivePermissionGrant() {
        try (CivicDatabase database = database()) {
            Fixtures fixtures = fixtures(database);
            NationId nationId = fixtures.nationId();
            fixtures.authorities().grant(new GrantNationFiscalPermission(
                    GOVERNANCE_SERVICE,
                    "grant-payment-approval-first",
                    nationId,
                    HEAD_ID,
                    CITIZEN_ID,
                    NationFiscalPermission.APPROVE_PAYMENT,
                    "First payment approver appointment"));

            assertThrows(
                    IllegalStateException.class,
                    () -> fixtures.authorities().grant(new GrantNationFiscalPermission(
                            GOVERNANCE_SERVICE,
                            "grant-payment-approval-second",
                            nationId,
                            HEAD_ID,
                            CITIZEN_ID,
                            NationFiscalPermission.APPROVE_PAYMENT,
                            "Second request must not alias the first")));
            assertEquals(
                    1,
                    fixtures.authorities().activeGrants(nationId, CITIZEN_ID).size());
        }
    }

    @Test
    void ordinaryCitizenCannotGrantFiscalAuthority() {
        try (CivicDatabase database = database()) {
            Fixtures fixtures = fixtures(database);

            assertThrows(
                    SecurityException.class,
                    () -> fixtures.authorities().grant(new GrantNationFiscalPermission(
                            GOVERNANCE_SERVICE,
                            "citizen-cannot-grant",
                            fixtures.nationId(),
                            CITIZEN_ID,
                            HEAD_ID,
                            NationFiscalPermission.MANAGE_FISCAL_ROLES,
                            "Ordinary FTB membership must not grant governance authority")));
            assertEquals(
                    Set.of(),
                    Set.copyOf(fixtures.authorities().activeGrants(fixtures.nationId())));
        }
    }

    private Fixtures fixtures(CivicDatabase database) {
        NationTeamDirectory teams = new NationTeamDirectory() {
            @Override
            public Optional<NationTeam> find(UUID teamId) {
                return TEAM_ID.equals(teamId)
                        ? Optional.of(new NationTeam(TEAM_ID, HEAD_ID, Set.of(HEAD_ID, CITIZEN_ID)))
                        : Optional.empty();
            }

            @Override
            public Optional<NationTeam> findEffectiveTeamForPlayer(UUID playerId) {
                return Set.of(HEAD_ID, CITIZEN_ID).contains(playerId) ? find(TEAM_ID) : Optional.empty();
            }
        };
        NationRegistry nations = new NationRegistry(database, teams);
        NationId nationId = nations.register(new RegisterNation(
                        new ServiceIdentity("civiceconomy"), "register-fiscal-authority", TEAM_ID))
                .nationId();
        CitizenshipRegistry citizenships = new CitizenshipRegistry(database, Duration.ZERO, CLOCK);
        joinIfMissing(citizenships, HEAD_ID, nationId, "join-head");
        joinIfMissing(citizenships, CITIZEN_ID, nationId, "join-citizen");
        CitizenshipCorrectionGraceRegistry corrections =
                new CitizenshipCorrectionGraceRegistry(database, CLOCK);
        NationProvider provider =
                new FtbTeamsNationProvider(nations, citizenships, corrections, teams);
        return new Fixtures(
                nationId, new NationFiscalAuthorityRegistry(database, provider, CLOCK));
    }

    private static void joinIfMissing(
            CitizenshipRegistry citizenships, UUID playerId, NationId nationId, String requestId) {
        if (citizenships.current(playerId).isEmpty()) {
            citizenships.join(new JoinCitizenship(
                    new ServiceIdentity("civiceconomy"), requestId, playerId, nationId));
        }
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("nation-fiscal-authority.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("4e00aec3-eef1-4655-a720-e9b8cc69daa6"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }

    private record Fixtures(
            NationId nationId, NationFiscalAuthorityRegistry authorities) {}
}
