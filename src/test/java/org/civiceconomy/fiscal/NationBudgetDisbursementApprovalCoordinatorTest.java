package org.civiceconomy.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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

class NationBudgetDisbursementApprovalCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-07-17T04:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID ACTOR =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TEAM =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @TempDir Path temporaryDirectory;

    @Test
    void missingInitiatePaymentAuthorityFailsBeforeApprovalPersistence() {
        try (CivicDatabase database = database()) {
            Fixtures fixtures = fixtures(database);
            Budget budget = approvedBudget(database, fixtures.nationId());
            NationBudgetDisbursementApprovalCoordinator coordinator =
                    new NationBudgetDisbursementApprovalCoordinator(
                            database, fixtures.provider(), fixtures.authorities(), CLOCK);

            assertThrows(
                    SecurityException.class,
                    () -> coordinator.initiate(
                            ACTOR,
                            budget.budgetId(),
                            new AccountId(
                                    "player:bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                            MoneyAmount.ofMinorUnits(100L),
                            "unauthorized-disbursement",
                            "Unauthorized milestone"));

            assertNull(database.budgetDisbursementApproval(
                    NationBudgetDisbursementApprovalCoordinator.SERVICE_IDENTITY.value(),
                    "unauthorized-disbursement"));
        }
    }

    @Test
    void authorizedInitiatorCreatesExactOwnBudgetApprovalWithoutFiscalEffect() {
        try (CivicDatabase database = database()) {
            Fixtures fixtures = fixtures(database);
            fixtures.authorities().grant(new GrantNationFiscalPermission(
                    new ServiceIdentity("civiceconomy-governance"),
                    "grant-disbursement-initiation",
                    fixtures.nationId(),
                    ACTOR,
                    ACTOR,
                    NationFiscalPermission.INITIATE_PAYMENT,
                    "Authorize governed Budget Disbursement initiation"));
            Budget budget = approvedBudget(database, fixtures.nationId());
            AccountId recipient = new AccountId(
                    "player:bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
            NationBudgetDisbursementApprovalCoordinator coordinator =
                    new NationBudgetDisbursementApprovalCoordinator(
                            database, fixtures.provider(), fixtures.authorities(), CLOCK);

            BudgetDisbursementApproval approval = coordinator.initiate(
                    ACTOR,
                    budget.budgetId(),
                    recipient,
                    MoneyAmount.ofMinorUnits(100L),
                    "authorized-disbursement",
                    "First governed milestone");

            assertEquals("APPROVED", approval.state());
            assertEquals(fixtures.nationId(), approval.nationId());
            assertEquals(budget.budgetId(), approval.budgetId());
            assertEquals(recipient, approval.recipientAccount());
            assertEquals(MoneyAmount.ofMinorUnits(100L), approval.amount());
            assertEquals("APPROVED", database.budget(budget.budgetId()).state());
            assertNull(database.paymentTransaction("authorized-disbursement"));
        }
    }

    @Test
    void missingApprovePaymentAuthorityFailsBeforeSecondVotePersistence() {
        try (CivicDatabase database = database()) {
            Fixtures fixtures = fixtures(database);
            fixtures.authorities().grant(new GrantNationFiscalPermission(
                    new ServiceIdentity("civiceconomy-governance"),
                    "grant-disbursement-initiation-only",
                    fixtures.nationId(),
                    ACTOR,
                    ACTOR,
                    NationFiscalPermission.INITIATE_PAYMENT,
                    "Authorize initiation but not approval"));
            new BudgetDisbursementApprovalPolicyRegistry(database, CLOCK)
                    .schedule(new ScheduleBudgetDisbursementApprovalPolicy(
                            new ServiceIdentity("civiceconomy-budget-governance"),
                            "schedule-two-person-coordinator-policy",
                            fixtures.nationId(),
                            ACTOR,
                            java.util.List.of(new BudgetDisbursementApprovalTier(
                                    MoneyAmount.ZERO, 2)),
                            Duration.ofDays(7L),
                            NOW.plusSeconds(60L),
                            "Require two Citizens for governed disbursement"));
            Budget budget = approvedBudget(database, fixtures.nationId());
            NationBudgetDisbursementApprovalCoordinator atEffective =
                    new NationBudgetDisbursementApprovalCoordinator(
                            database,
                            fixtures.provider(),
                            fixtures.authorities(),
                            Clock.fixed(NOW.plusSeconds(60L), ZoneOffset.UTC));
            BudgetDisbursementApproval pending = atEffective.initiate(
                    ACTOR,
                    budget.budgetId(),
                    new AccountId("player:bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                    MoneyAmount.ofMinorUnits(100L),
                    "pending-two-person-disbursement",
                    "Pending governed milestone");

            assertThrows(
                    SecurityException.class,
                    () -> atEffective.approve(
                            ACTOR,
                            pending.approvalRequestId(),
                            "unauthorized-second-vote",
                            "Cannot approve without APPROVE_PAYMENT"));

            assertEquals(
                    java.util.List.of(ACTOR),
                    new BudgetDisbursementApprovalRegistry(
                                    database,
                                    Clock.fixed(NOW.plusSeconds(60L), ZoneOffset.UTC))
                            .find(pending.approvalRequestId())
                            .approverPlayerIds());
        }
    }

    private static Budget approvedBudget(CivicDatabase database, NationId nationId) {
        UUID budgetId = UUID.randomUUID();
        database.createBudget(
                budgetId,
                BudgetFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                "create-coordinator-budget",
                "nation:" + nationId.value() + ":treasury",
                300L,
                "PUBLIC_WORKS",
                "Coordinator Budget",
                NOW.plus(Duration.ofDays(30L)).toEpochMilli());
        return FiscalLedger.toBudget(database.approveBudget(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                BudgetFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                "approve-coordinator-budget",
                budgetId,
                ACTOR,
                "Approve coordinator Budget",
                NOW.minusSeconds(60L).toEpochMilli()));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("nation-budget-disbursement.sqlite3"),
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
                return TEAM.equals(teamId)
                        ? Optional.of(new NationTeam(TEAM, ACTOR, Set.of(ACTOR)))
                        : Optional.empty();
            }

            @Override
            public Optional<NationTeam> findEffectiveTeamForPlayer(UUID playerId) {
                return ACTOR.equals(playerId) ? find(TEAM) : Optional.empty();
            }
        };
        NationRegistry nations = new NationRegistry(database, teams);
        NationId nationId = nations.register(new RegisterNation(
                new ServiceIdentity("civiceconomy"),
                "register-budget-disbursement",
                TEAM)).nationId();
        CitizenshipRegistry citizenships =
                new CitizenshipRegistry(database, Duration.ZERO, CLOCK);
        citizenships.join(new JoinCitizenship(
                new ServiceIdentity("civiceconomy"),
                "join-budget-disbursement",
                ACTOR,
                nationId));
        NationProvider provider = new FtbTeamsNationProvider(
                nations,
                citizenships,
                new CitizenshipCorrectionGraceRegistry(database, CLOCK),
                teams);
        return new Fixtures(
                nationId,
                provider,
                new NationFiscalAuthorityRegistry(database, provider, CLOCK));
    }

    private record Fixtures(
            NationId nationId,
            NationProvider provider,
            NationFiscalAuthorityRegistry authorities) {}
}
