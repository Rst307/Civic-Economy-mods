package org.civiceconomy.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

class NationBudgetApprovalCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID ACTOR =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TEAM =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @TempDir Path temporaryDirectory;

    @Test
    void missingApproveBudgetAuthorityFailsBeforeServiceProvisioningOrHoldCreation() {
        try (CivicDatabase database = database()) {
            Fixtures fixtures = fixtures(database);
            Budget draft = createBudget(database, fixtures.nationId(), "unauthorized-approval");
            NationBudgetApprovalCoordinator coordinator = coordinator(database, fixtures);

            assertThrows(
                    SecurityException.class,
                    () -> coordinator.approve(
                            ACTOR,
                            draft.budgetId(),
                            "unauthorized-approval-request",
                            "Unauthorized Budget approval",
                            ignored -> MoneyAmount.ofMinorUnits(1_000L)));

            assertNull(database.fiscalService(
                    BudgetFiscalServiceProvisioner.SERVICE_IDENTITY.value()));
            assertNull(database.budget(draft.budgetId()).escrowId());
            assertEquals("DRAFT", database.budget(draft.budgetId()).state());
        }
    }

    @Test
    void sameCitizenMayApproveOnceAndChangedBudgetReplayConflicts() {
        try (CivicDatabase database = database()) {
            Fixtures fixtures = fixtures(database);
            fixtures.authorities().grant(new GrantNationFiscalPermission(
                    new ServiceIdentity("civiceconomy-governance"),
                    "grant-budget-approval",
                    fixtures.nationId(),
                    ACTOR,
                    ACTOR,
                    NationFiscalPermission.APPROVE_BUDGET,
                    "Budget drafter may also approve"));
            Budget draft = createBudget(database, fixtures.nationId(), "approved-budget");
            Budget other = createBudget(database, fixtures.nationId(), "other-budget");
            NationBudgetApprovalCoordinator coordinator = coordinator(database, fixtures);
            AccountBalances balances = ignored -> MoneyAmount.ofMinorUnits(1_000L);

            Budget approved = coordinator.approve(
                    ACTOR,
                    draft.budgetId(),
                    "approve-budget",
                    "Approve public works allocation",
                    balances);

            assertEquals(BudgetState.APPROVED, approved.state());
            assertTrue(approved.escrowId().isPresent());
            assertEquals(MoneyAmount.ofMinorUnits(300L), approved.remainingAmount());
            var escrow = database.escrow(approved.escrowId().orElseThrow());
            assertEquals(
                    "nation:" + fixtures.nationId().value() + ":treasury",
                    escrow.sourceAccount());
            assertEquals(300L, escrow.amountMinorUnits());
            assertEquals("RESERVED", escrow.state());
            assertEquals(
                    approved,
                    coordinator.approve(
                            ACTOR,
                            draft.budgetId(),
                            "approve-budget",
                            "Approve public works allocation",
                            balances));
            assertThrows(
                    IdempotencyConflictException.class,
                    () -> coordinator.approve(
                            ACTOR,
                            other.budgetId(),
                            "approve-budget",
                            "Approve public works allocation",
                            balances));
            assertNull(database.budget(other.budgetId()).escrowId());
            assertEquals("DRAFT", database.budget(other.budgetId()).state());
            var audit = database.budgetApprovalAudit(draft.budgetId());
            assertEquals(ACTOR, audit.actorPlayerId());
            assertEquals("Approve public works allocation", audit.reason());
            assertEquals(NOW.toEpochMilli(), audit.approvedAtEpochMillis());

            var authorization = new FiscalAuthorization(database)
                    .describe(BudgetFiscalServiceProvisioner.SERVICE_IDENTITY);
            assertEquals(1, authorization.grants().size());
            assertEquals(
                    FiscalCapability.MANAGE_BUDGET,
                    authorization.grants().getFirst().grant().capability());
            assertEquals(
                    new AccountId("nation:" + fixtures.nationId().value() + ":treasury"),
                    authorization.grants().getFirst().grant().accountId());
        }
    }

    @Test
    void missingApproveBudgetAuthorityFailsBeforeBudgetCancellationProvisioningOrRelease() {
        try (CivicDatabase database = database()) {
            Fixtures fixtures = fixtures(database);
            Budget draft = createBudget(database, fixtures.nationId(), "cancel-without-authority");
            database.approveBudget(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    BudgetFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                    "prepare-cancellation",
                    draft.budgetId(),
                    ACTOR,
                    "Prepare approved Budget fixture",
                    NOW.toEpochMilli());
            NationBudgetCancellationCoordinator coordinator =
                    new NationBudgetCancellationCoordinator(
                            database,
                            fixtures.provider(),
                            fixtures.authorities(),
                            CLOCK,
                            authorization -> authorization.openSession(
                                    BudgetFiscalServiceProvisioner.SERVICE_IDENTITY,
                                    "civiceconomy"));

            assertThrows(
                    SecurityException.class,
                    () -> coordinator.cancel(
                            ACTOR,
                            draft.budgetId(),
                            "cancel-without-authority",
                            "Cancel unauthorized Budget"));

            assertNull(database.fiscalService(
                    BudgetFiscalServiceProvisioner.SERVICE_IDENTITY.value()));
            assertEquals("APPROVED", database.budget(draft.budgetId()).state());
            assertNull(database.budgetCancellationAudit(draft.budgetId()));
        }
    }

    @Test
    void foreignTreasuryBudgetFailsBeforeCancellationServiceProvisioningOrRelease() {
        try (CivicDatabase database = database()) {
            Fixtures fixtures = fixtures(database);
            fixtures.authorities().grant(new GrantNationFiscalPermission(
                    new ServiceIdentity("civiceconomy-governance"),
                    "grant-foreign-budget-cancellation",
                    fixtures.nationId(),
                    ACTOR,
                    ACTOR,
                    NationFiscalPermission.APPROVE_BUDGET,
                    "Budget approver remains exact-Nation scoped"));
            Budget draft = FiscalLedger.toBudget(database.createBudget(
                    UUID.randomUUID(),
                    BudgetFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                    "foreign-cancel-budget",
                    "nation:bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb:treasury",
                    300L,
                    "PUBLIC_WORKS",
                    "Foreign Treasury Budget",
                    NOW.plusSeconds(3_600L).toEpochMilli()));
            database.approveBudget(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    BudgetFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                    "approve-foreign-cancel-budget",
                    draft.budgetId(),
                    ACTOR,
                    "Prepare foreign approved Budget fixture",
                    NOW.toEpochMilli());
            NationBudgetCancellationCoordinator coordinator =
                    new NationBudgetCancellationCoordinator(
                            database,
                            fixtures.provider(),
                            fixtures.authorities(),
                            CLOCK,
                            authorization -> authorization.openSession(
                                    BudgetFiscalServiceProvisioner.SERVICE_IDENTITY,
                                    "civiceconomy"));

            assertThrows(
                    SecurityException.class,
                    () -> coordinator.cancel(
                            ACTOR,
                            draft.budgetId(),
                            "cancel-foreign-budget",
                            "Cannot cancel another Nation's Budget"));

            assertNull(database.fiscalService(
                    BudgetFiscalServiceProvisioner.SERVICE_IDENTITY.value()));
            assertEquals("APPROVED", database.budget(draft.budgetId()).state());
            assertNull(database.budgetCancellationAudit(draft.budgetId()));
        }
    }

    @Test
    void authorizedCancellationReleasesOnlyTheRemainingHoldAndReplaysExactlyOnce() {
        try (CivicDatabase database = database()) {
            Fixtures fixtures = fixtures(database);
            fixtures.authorities().grant(new GrantNationFiscalPermission(
                    new ServiceIdentity("civiceconomy-governance"),
                    "grant-budget-cancellation",
                    fixtures.nationId(),
                    ACTOR,
                    ACTOR,
                    NationFiscalPermission.APPROVE_BUDGET,
                    "Budget approver may cancel remaining work"));
            Budget draft = createBudget(database, fixtures.nationId(), "cancel-partial-budget");
            Budget approved = coordinator(database, fixtures).approve(
                    ACTOR,
                    draft.budgetId(),
                    "approve-before-cancel",
                    "Approve work before partial settlement",
                    ignored -> MoneyAmount.ofMinorUnits(1_000L));
            var escrow = database.escrow(approved.escrowId().orElseThrow());
            new PaymentCoordinator(database, ignored -> {}).settle(
                    new SettleReservation(
                            BudgetFiscalServiceProvisioner.SERVICE_IDENTITY,
                            "partial-before-cancel",
                            escrow.reservationId(),
                            new AccountId("organization:builder:fiscal"),
                            MoneyAmount.ofMinorUnits(100L)),
                    FailurePoint.NONE);
            NationBudgetCancellationCoordinator cancellation =
                    new NationBudgetCancellationCoordinator(
                            database,
                            fixtures.provider(),
                            fixtures.authorities(),
                            CLOCK,
                            authorization -> authorization.openSession(
                                    BudgetFiscalServiceProvisioner.SERVICE_IDENTITY,
                                    "civiceconomy"));

            Budget released = cancellation.cancel(
                    ACTOR,
                    draft.budgetId(),
                    "cancel-budget",
                    "Cancel unfinished public works");

            assertEquals(BudgetState.RELEASED, released.state());
            assertEquals(MoneyAmount.ofMinorUnits(100L), released.settledAmount());
            assertEquals(MoneyAmount.ofMinorUnits(200L), released.remainingAmount());
            assertEquals("RELEASED", database.reservationRecord(escrow.reservationId()).state());
            assertEquals("RELEASED", database.escrow(escrow.escrowId()).state());
            var audit = database.budgetCancellationAudit(draft.budgetId());
            assertEquals(ACTOR, audit.actorPlayerId());
            assertEquals("Cancel unfinished public works", audit.reason());
            assertEquals(NOW.toEpochMilli(), audit.cancelledAtEpochMillis());
            assertEquals(
                    released,
                    cancellation.cancel(
                            ACTOR,
                            draft.budgetId(),
                            "cancel-budget",
                            "Cancel unfinished public works"));
            assertThrows(
                    IdempotencyConflictException.class,
                    () -> cancellation.cancel(
                            ACTOR,
                            draft.budgetId(),
                            "cancel-budget",
                            "Changed cancellation reason"));
        }
    }

    @Test
    void preparedPaymentBlocksBudgetCancellationWithoutPartialAuditOrRelease() {
        try (CivicDatabase database = database()) {
            Fixtures fixtures = fixtures(database);
            fixtures.authorities().grant(new GrantNationFiscalPermission(
                    new ServiceIdentity("civiceconomy-governance"),
                    "grant-blocked-budget-cancellation",
                    fixtures.nationId(),
                    ACTOR,
                    ACTOR,
                    NationFiscalPermission.APPROVE_BUDGET,
                    "Budget approver may cancel only safe remaining work"));
            Budget draft = createBudget(database, fixtures.nationId(), "blocked-cancel-budget");
            Budget approved = coordinator(database, fixtures).approve(
                    ACTOR,
                    draft.budgetId(),
                    "approve-before-blocked-cancel",
                    "Approve work before prepared Payment",
                    ignored -> MoneyAmount.ofMinorUnits(1_000L));
            Escrow escrow = FiscalLedger.toEscrow(
                    database.escrow(approved.escrowId().orElseThrow()));
            new PaymentCoordinator(database, ignored -> {}).prepare(new SettleReservation(
                    BudgetFiscalServiceProvisioner.SERVICE_IDENTITY,
                    "prepared-before-budget-cancel",
                    escrow.reservationId(),
                    new AccountId("organization:builder:fiscal"),
                    MoneyAmount.ofMinorUnits(100L)));
            NationBudgetCancellationCoordinator cancellation =
                    new NationBudgetCancellationCoordinator(
                            database,
                            fixtures.provider(),
                            fixtures.authorities(),
                            CLOCK,
                            authorization -> authorization.openSession(
                                    BudgetFiscalServiceProvisioner.SERVICE_IDENTITY,
                                    "civiceconomy"));

            assertThrows(
                    ReservationHasPendingPaymentException.class,
                    () -> cancellation.cancel(
                            ACTOR,
                            draft.budgetId(),
                            "blocked-budget-cancel",
                            "Payment is already pending"));

            assertNull(database.budgetCancellationAudit(draft.budgetId()));
            assertNull(database.reservationRelease(
                    BudgetFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                    "blocked-budget-cancel"));
            assertEquals("APPROVED", database.budget(draft.budgetId()).state());
            assertEquals("RESERVED", database.escrow(escrow.escrowId()).state());
            assertEquals("ACTIVE", database.reservationRecord(escrow.reservationId()).state());
        }
    }

    private static NationBudgetApprovalCoordinator coordinator(
            CivicDatabase database, Fixtures fixtures) {
        return new NationBudgetApprovalCoordinator(
                database,
                fixtures.provider(),
                fixtures.authorities(),
                CLOCK,
                authorization -> authorization.openSession(
                        BudgetFiscalServiceProvisioner.SERVICE_IDENTITY,
                        "civiceconomy"));
    }

    private static Budget createBudget(
            CivicDatabase database, NationId nationId, String requestId) {
        return FiscalLedger.toBudget(database.createBudget(
                UUID.randomUUID(),
                BudgetFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                requestId,
                "nation:" + nationId.value() + ":treasury",
                300L,
                "PUBLIC_WORKS",
                "Approval " + requestId,
                NOW.plusSeconds(3_600L).toEpochMilli()));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("nation-budget-approval.sqlite3"),
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
                "register-budget-approval",
                TEAM)).nationId();
        CitizenshipRegistry citizenships = new CitizenshipRegistry(
                database, Duration.ZERO, CLOCK);
        citizenships.join(new JoinCitizenship(
                new ServiceIdentity("civiceconomy"),
                "join-budget-approval",
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
