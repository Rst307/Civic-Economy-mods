package org.civiceconomy.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WithdrawalApprovalPolicyRegistryTest {
    private static final Instant NOW = Instant.parse("2026-07-16T06:00:00Z");
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-07-23T00:00:00Z");
    private static final NationId NATION_ID =
            new NationId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final UUID ACTOR =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir Path temporaryDirectory;

    @Test
    void futureEffectiveTieredPolicyIsNationScopedAndSurvivesRestart() {
        WithdrawalApprovalPolicyVersion scheduled;
        ScheduleWithdrawalApprovalPolicy request = new ScheduleWithdrawalApprovalPolicy(
                new ServiceIdentity("civiceconomy-withdrawal-governance"),
                "schedule-withdrawal-policy-july",
                NATION_ID,
                ACTOR,
                List.of(
                        new WithdrawalApprovalTier(MoneyAmount.ZERO, 1),
                        new WithdrawalApprovalTier(MoneyAmount.ofMinorUnits(500L), 2),
                        new WithdrawalApprovalTier(MoneyAmount.ofMinorUnits(2_000L), 3)),
                EFFECTIVE_AT,
                "Require additional approval for larger cash withdrawals");

        try (CivicDatabase database = database()) {
            registerNation(database);
            WithdrawalApprovalPolicyRegistry policies = registry(database);

            scheduled = policies.schedule(request);

            assertEquals(scheduled, policies.schedule(request));
            assertEquals(
                    1,
                    policies.current(NATION_ID, EFFECTIVE_AT.minusMillis(1))
                            .requiredApprovals(MoneyAmount.ofMinorUnits(5_000L)));
            assertEquals(
                    1,
                    policies.current(NATION_ID, EFFECTIVE_AT)
                            .requiredApprovals(MoneyAmount.ofMinorUnits(499L)));
            assertEquals(
                    2,
                    policies.current(NATION_ID, EFFECTIVE_AT)
                            .requiredApprovals(MoneyAmount.ofMinorUnits(500L)));
            assertEquals(
                    3,
                    policies.current(NATION_ID, EFFECTIVE_AT)
                            .requiredApprovals(MoneyAmount.ofMinorUnits(2_000L)));
        }

        try (CivicDatabase reopened = database()) {
            assertEquals(scheduled, registry(reopened).current(NATION_ID, EFFECTIVE_AT));
        }
    }

    @Test
    void schedulingRejectsImmediateEffectAndChangedReplayPayload() {
        ScheduleWithdrawalApprovalPolicy request = new ScheduleWithdrawalApprovalPolicy(
                new ServiceIdentity("civiceconomy-withdrawal-governance"),
                "schedule-withdrawal-policy-conflict",
                NATION_ID,
                ACTOR,
                List.of(new WithdrawalApprovalTier(MoneyAmount.ZERO, 2)),
                EFFECTIVE_AT,
                "Two approvers");

        try (CivicDatabase database = database()) {
            registerNation(database);
            WithdrawalApprovalPolicyRegistry policies = registry(database);
            policies.schedule(request);

            assertThrows(
                    IdempotencyConflictException.class,
                    () -> policies.schedule(new ScheduleWithdrawalApprovalPolicy(
                            request.serviceIdentity(),
                            request.requestId(),
                            request.nationId(),
                            request.actorPlayerId(),
                            List.of(new WithdrawalApprovalTier(MoneyAmount.ZERO, 3)),
                            request.effectiveAt(),
                            request.reason())));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> policies.schedule(new ScheduleWithdrawalApprovalPolicy(
                            request.serviceIdentity(),
                            "schedule-now",
                            request.nationId(),
                            request.actorPlayerId(),
                            request.tiers(),
                            NOW,
                            request.reason())));
        }
    }

    private WithdrawalApprovalPolicyRegistry registry(CivicDatabase database) {
        return new WithdrawalApprovalPolicyRegistry(
                database,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("withdrawal-approval-policy.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("33333333-3333-3333-3333-333333333333"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }

    private static void registerNation(CivicDatabase database) {
        database.registerNation(
                NATION_ID.value(),
                "withdrawal-policy-test",
                "register-nation",
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                NOW.minusSeconds(60L).toEpochMilli());
    }
}
