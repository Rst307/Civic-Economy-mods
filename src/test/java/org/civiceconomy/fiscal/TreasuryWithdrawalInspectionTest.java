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

class TreasuryWithdrawalInspectionTest {
    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID TEAM_ONE =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TEAM_TWO =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CITIZEN_ONE =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CITIZEN_TWO =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID APPROVER_ONE =
            UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    private static final UUID OUTSIDER =
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @TempDir Path temporaryDirectory;

    @Test
    void currentPolicyIsDerivedFromTheActorsEffectiveCitizenship() {
        try (CivicDatabase database = database()) {
            Fixtures fixtures = fixtures(database);
            WithdrawalApprovalPolicyRegistry policies = new WithdrawalApprovalPolicyRegistry(
                    database, Clock.fixed(NOW.minus(Duration.ofDays(2)), ZoneOffset.UTC));
            WithdrawalApprovalPolicyVersion ownPolicy = policies.schedule(
                    policy("policy-one", fixtures.nationOne(), CITIZEN_ONE, 2));
            WithdrawalApprovalPolicyVersion futurePolicy = policies.schedule(
                    new ScheduleWithdrawalApprovalPolicy(
                            new ServiceIdentity("civiceconomy-withdrawal-governance"),
                            "policy-one-future",
                            fixtures.nationOne(),
                            CITIZEN_ONE,
                            List.of(new WithdrawalApprovalTier(
                                    MoneyAmount.ZERO, 3)),
                            Duration.ofDays(3L),
                            NOW.plus(Duration.ofDays(1L)),
                            "Future inspection policy"));
            policies.schedule(policy("policy-two", fixtures.nationTwo(), CITIZEN_TWO, 3));
            TreasuryWithdrawalInspection inspection = new TreasuryWithdrawalInspection(
                    fixtures.provider(),
                    fixtures.authorities(),
                    policies,
                    new TreasuryWithdrawalApprovalRegistry(database, CLOCK),
                    CLOCK);

            assertEquals(ownPolicy, inspection.currentPolicy(CITIZEN_ONE));
            assertEquals(
                    List.of(ownPolicy, futurePolicy),
                    inspection.policyHistory(CITIZEN_ONE));
            assertThrows(SecurityException.class, () -> inspection.currentPolicy(OUTSIDER));
            assertThrows(SecurityException.class, () -> inspection.policyHistory(OUTSIDER));
        }
    }

    @Test
    void approvalInspectionRequiresExactNationAuthorityAndRejectsForeignIds() {
        try (CivicDatabase database = database()) {
            Fixtures fixtures = fixtures(database);
            WithdrawalApprovalPolicyRegistry policies = new WithdrawalApprovalPolicyRegistry(
                    database, Clock.fixed(NOW.minus(Duration.ofDays(2)), ZoneOffset.UTC));
            policies.schedule(policy("policy-one", fixtures.nationOne(), CITIZEN_ONE, 2));
            policies.schedule(policy("policy-two", fixtures.nationTwo(), CITIZEN_TWO, 2));
            fixtures.authorities().grant(new GrantNationFiscalPermission(
                    new ServiceIdentity("civiceconomy-governance"),
                    "grant-withdrawal-inspection",
                    fixtures.nationOne(),
                    CITIZEN_ONE,
                    APPROVER_ONE,
                    NationFiscalPermission.MANAGE_WITHDRAWAL,
                    "Treasury officer may inspect and approve withdrawals"));
            TreasuryWithdrawalApprovalRegistry approvals =
                    new TreasuryWithdrawalApprovalRegistry(database, CLOCK);
            TreasuryWithdrawalApproval own = approvals.initiate(new ConfirmTreasuryWithdrawal(
                    new ServiceIdentity("civiceconomy-treasury-withdrawal"),
                    "withdraw-own",
                    fixtures.nationOne(),
                    new AccountId("nation:" + fixtures.nationOne().value() + ":treasury"),
                    CITIZEN_ONE,
                    MoneyAmount.ofMinorUnits(500L),
                    "Own Nation public works cash"));
            TreasuryWithdrawalApproval foreign = approvals.initiate(
                    new ConfirmTreasuryWithdrawal(
                            new ServiceIdentity("civiceconomy-treasury-withdrawal"),
                            "withdraw-foreign",
                            fixtures.nationTwo(),
                            new AccountId("nation:" + fixtures.nationTwo().value() + ":treasury"),
                            CITIZEN_TWO,
                            MoneyAmount.ofMinorUnits(600L),
                            "Foreign Nation public works cash"));
            TreasuryWithdrawalInspection inspection = new TreasuryWithdrawalInspection(
                    fixtures.provider(), fixtures.authorities(), policies, approvals, CLOCK);

            assertEquals(
                    List.of(new TreasuryWithdrawalApprovalStatus(own, true)),
                    inspection.approvalStatuses(APPROVER_ONE));
            assertEquals(
                    new TreasuryWithdrawalApprovalStatus(own, true),
                    inspection.approvalStatus(APPROVER_ONE, own.approvalRequestId()));
            assertThrows(
                    SecurityException.class,
                    () -> inspection.approvalStatus(
                            APPROVER_ONE, foreign.approvalRequestId()));
            assertThrows(
                    SecurityException.class,
                    () -> inspection.approvalStatus(
                            APPROVER_ONE,
                            UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")));
            assertThrows(
                    SecurityException.class,
                    () -> inspection.approvalStatuses(CITIZEN_ONE));
            TreasuryWithdrawalApproval cancelled = inspection.cancel(
                    APPROVER_ONE,
                    own.approvalRequestId(),
                    "cancel-own-withdrawal",
                    "Treasury officer cancelled stale request");
            assertEquals("CANCELLED", cancelled.state());
            assertThrows(
                    SecurityException.class,
                    () -> inspection.cancel(
                            APPROVER_ONE,
                            foreign.approvalRequestId(),
                            "cancel-foreign-withdrawal",
                            "Cannot cancel another Nation's request"));
        }
    }

    private static ScheduleWithdrawalApprovalPolicy policy(
            String requestId, NationId nationId, UUID actor, int required) {
        return new ScheduleWithdrawalApprovalPolicy(
                new ServiceIdentity("civiceconomy-withdrawal-governance"),
                requestId,
                nationId,
                actor,
                List.of(new WithdrawalApprovalTier(MoneyAmount.ZERO, required)),
                Duration.ofDays(7L),
                NOW.minus(Duration.ofDays(1)),
                "Inspection policy " + required);
    }

    private Fixtures fixtures(CivicDatabase database) {
        NationTeamDirectory teams = new NationTeamDirectory() {
            @Override
            public Optional<NationTeam> find(UUID teamId) {
                if (TEAM_ONE.equals(teamId)) {
                    return Optional.of(new NationTeam(
                            TEAM_ONE, CITIZEN_ONE, Set.of(CITIZEN_ONE, APPROVER_ONE)));
                }
                if (TEAM_TWO.equals(teamId)) {
                    return Optional.of(new NationTeam(
                            TEAM_TWO, CITIZEN_TWO, Set.of(CITIZEN_TWO)));
                }
                return Optional.empty();
            }

            @Override
            public Optional<NationTeam> findEffectiveTeamForPlayer(UUID playerId) {
                if (Set.of(CITIZEN_ONE, APPROVER_ONE).contains(playerId)) {
                    return find(TEAM_ONE);
                }
                if (CITIZEN_TWO.equals(playerId)) {
                    return find(TEAM_TWO);
                }
                return Optional.empty();
            }
        };
        NationRegistry nations = new NationRegistry(database, teams);
        NationId nationOne = nations.register(new RegisterNation(
                new ServiceIdentity("civiceconomy"), "register-one", TEAM_ONE)).nationId();
        NationId nationTwo = nations.register(new RegisterNation(
                new ServiceIdentity("civiceconomy"), "register-two", TEAM_TWO)).nationId();
        CitizenshipRegistry citizenships = new CitizenshipRegistry(database, Duration.ZERO, CLOCK);
        citizenships.join(new JoinCitizenship(
                new ServiceIdentity("civiceconomy"), "join-one", CITIZEN_ONE, nationOne));
        citizenships.join(new JoinCitizenship(
                new ServiceIdentity("civiceconomy"), "join-approver-one", APPROVER_ONE, nationOne));
        citizenships.join(new JoinCitizenship(
                new ServiceIdentity("civiceconomy"), "join-two", CITIZEN_TWO, nationTwo));
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

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("treasury-withdrawal-inspection.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }

    private record Fixtures(
            NationId nationOne,
            NationId nationTwo,
            NationProvider provider,
            NationFiscalAuthorityRegistry authorities) {}
}
