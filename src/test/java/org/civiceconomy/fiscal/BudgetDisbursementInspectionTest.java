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

class BudgetDisbursementInspectionTest {
    private static final Instant NOW = Instant.parse("2026-07-17T06:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID TEAM_ONE =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TEAM_TWO =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CITIZEN_ONE =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID APPROVER_ONE =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID CITIZEN_TWO =
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID OUTSIDER =
            UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @TempDir Path temporaryDirectory;

    @Test
    void policyInspectionDerivesTheActorsNationAndReturnsDeterministicHistory() {
        try (CivicDatabase database = database()) {
            Fixtures fixtures = fixtures(database);
            BudgetDisbursementApprovalPolicyRegistry policies =
                    new BudgetDisbursementApprovalPolicyRegistry(
                            database,
                            Clock.fixed(NOW.minus(Duration.ofDays(3L)), ZoneOffset.UTC));
            BudgetDisbursementApprovalPolicyVersion current = policies.schedule(policy(
                    "own-current-policy", fixtures.nationOne(), CITIZEN_ONE, 2,
                    NOW.minus(Duration.ofDays(2L))));
            BudgetDisbursementApprovalPolicyVersion future = policies.schedule(policy(
                    "own-future-policy", fixtures.nationOne(), CITIZEN_ONE, 3,
                    NOW.plus(Duration.ofDays(1L))));
            policies.schedule(policy(
                    "foreign-policy", fixtures.nationTwo(), CITIZEN_TWO, 4,
                    NOW.minus(Duration.ofDays(1L))));
            BudgetDisbursementInspection inspection = new BudgetDisbursementInspection(
                    fixtures.provider(),
                    fixtures.authorities(),
                    policies,
                    new BudgetDisbursementApprovalRegistry(database, CLOCK),
                    CLOCK);

            assertEquals(current, inspection.currentPolicy(CITIZEN_ONE));
            assertEquals(List.of(current, future), inspection.policyHistory(CITIZEN_ONE));
            assertThrows(SecurityException.class, () -> inspection.currentPolicy(OUTSIDER));
            assertThrows(SecurityException.class, () -> inspection.policyHistory(OUTSIDER));
        }
    }

    @Test
    void approvalInspectionRequiresApprovePaymentAndHidesForeignAndUnknownIds() {
        try (CivicDatabase database = database()) {
            Fixtures fixtures = fixtures(database);
            BudgetDisbursementApprovalPolicyRegistry policies =
                    new BudgetDisbursementApprovalPolicyRegistry(
                            database,
                            Clock.fixed(NOW.minus(Duration.ofDays(3L)), ZoneOffset.UTC));
            policies.schedule(policy(
                    "own-two-person-policy", fixtures.nationOne(), CITIZEN_ONE, 2,
                    NOW.minus(Duration.ofDays(2L))));
            policies.schedule(policy(
                    "foreign-two-person-policy", fixtures.nationTwo(), CITIZEN_TWO, 2,
                    NOW.minus(Duration.ofDays(2L))));
            Budget ownBudget = approvedBudget(database, fixtures.nationOne(), "own");
            Budget foreignBudget = approvedBudget(database, fixtures.nationTwo(), "foreign");
            BudgetDisbursementApprovalRegistry approvals =
                    new BudgetDisbursementApprovalRegistry(database, CLOCK);
            BudgetDisbursementApproval own = approvals.initiate(new InitiateBudgetDisbursementApproval(
                    NationBudgetDisbursementApprovalCoordinator.SERVICE_IDENTITY,
                    "own-disbursement-inspection",
                    fixtures.nationOne(),
                    ownBudget.budgetId(),
                    new AccountId("player:eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"),
                    MoneyAmount.ofMinorUnits(100L),
                    CITIZEN_ONE,
                    "Own inspection milestone"));
            BudgetDisbursementApproval foreign = approvals.initiate(
                    new InitiateBudgetDisbursementApproval(
                            NationBudgetDisbursementApprovalCoordinator.SERVICE_IDENTITY,
                            "foreign-disbursement-inspection",
                            fixtures.nationTwo(),
                            foreignBudget.budgetId(),
                            new AccountId(
                                    "player:ffffffff-ffff-ffff-ffff-ffffffffffff"),
                            MoneyAmount.ofMinorUnits(100L),
                            CITIZEN_TWO,
                            "Foreign inspection milestone"));
            fixtures.authorities().grant(new GrantNationFiscalPermission(
                    new ServiceIdentity("civiceconomy-governance"),
                    "grant-disbursement-inspection",
                    fixtures.nationOne(),
                    CITIZEN_ONE,
                    APPROVER_ONE,
                    NationFiscalPermission.APPROVE_PAYMENT,
                    "Treasury officer may inspect Budget Disbursement approvals"));
            BudgetDisbursementInspection inspection = new BudgetDisbursementInspection(
                    fixtures.provider(), fixtures.authorities(), policies, approvals, CLOCK);

            assertEquals(
                    List.of(new BudgetDisbursementApprovalStatus(own, true)),
                    inspection.approvalStatuses(APPROVER_ONE));
            assertEquals(
                    new BudgetDisbursementApprovalStatus(own, true),
                    inspection.approvalStatus(APPROVER_ONE, own.approvalRequestId()));
            assertThrows(
                    SecurityException.class,
                    () -> inspection.approvalStatus(
                            APPROVER_ONE, foreign.approvalRequestId()));
            assertThrows(
                    SecurityException.class,
                    () -> inspection.approvalStatus(
                            APPROVER_ONE,
                            UUID.fromString("99999999-9999-9999-9999-999999999999")));
            assertThrows(
                    SecurityException.class,
                    () -> inspection.approvalStatuses(CITIZEN_ONE));
        }
    }

    private static ScheduleBudgetDisbursementApprovalPolicy policy(
            String requestId,
            NationId nationId,
            UUID actor,
            int requiredApprovals,
            Instant effectiveAt) {
        return new ScheduleBudgetDisbursementApprovalPolicy(
                new ServiceIdentity("civiceconomy-budget-disbursement-governance"),
                requestId,
                nationId,
                actor,
                List.of(new BudgetDisbursementApprovalTier(
                        MoneyAmount.ZERO, requiredApprovals)),
                Duration.ofDays(7L),
                effectiveAt,
                "Inspection policy " + requiredApprovals);
    }

    private static Budget approvedBudget(
            CivicDatabase database, NationId nationId, String requestSuffix) {
        UUID budgetId = UUID.randomUUID();
        database.createBudget(
                budgetId,
                BudgetFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                "create-" + requestSuffix + "-inspection-budget",
                "nation:" + nationId.value() + ":treasury",
                300L,
                "PUBLIC_WORKS",
                requestSuffix + " inspection Budget",
                NOW.plus(Duration.ofDays(30L)).toEpochMilli());
        return FiscalLedger.toBudget(database.approveBudget(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                BudgetFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                "approve-" + requestSuffix + "-inspection-budget",
                budgetId,
                CITIZEN_ONE,
                "Approve inspection Budget",
                NOW.minusSeconds(60L).toEpochMilli()));
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
                return CITIZEN_TWO.equals(playerId) ? find(TEAM_TWO) : Optional.empty();
            }
        };
        NationRegistry nations = new NationRegistry(database, teams);
        NationId nationOne = nations.register(new RegisterNation(
                new ServiceIdentity("civiceconomy"), "register-inspection-one", TEAM_ONE))
                .nationId();
        NationId nationTwo = nations.register(new RegisterNation(
                new ServiceIdentity("civiceconomy"), "register-inspection-two", TEAM_TWO))
                .nationId();
        CitizenshipRegistry citizenships = new CitizenshipRegistry(database, Duration.ZERO, CLOCK);
        citizenships.join(new JoinCitizenship(
                new ServiceIdentity("civiceconomy"),
                "join-inspection-one",
                CITIZEN_ONE,
                nationOne));
        citizenships.join(new JoinCitizenship(
                new ServiceIdentity("civiceconomy"),
                "join-inspection-approver",
                APPROVER_ONE,
                nationOne));
        citizenships.join(new JoinCitizenship(
                new ServiceIdentity("civiceconomy"),
                "join-inspection-two",
                CITIZEN_TWO,
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

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("budget-disbursement-inspection.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("12121212-1212-1212-1212-121212121212"),
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
