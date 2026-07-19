package org.civiceconomy.fiscal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredBudgetDisbursementApprovalPolicy;
import org.civiceconomy.persistence.StoredBudgetDisbursementApprovalTier;

public final class BudgetDisbursementApprovalPolicyRegistry {
    private final CivicDatabase database;
    private final Clock clock;

    public BudgetDisbursementApprovalPolicyRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException(
                    "Budget disbursement approval policy dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public BudgetDisbursementApprovalPolicyVersion schedule(
            ScheduleBudgetDisbursementApprovalPolicy request) {
        StoredBudgetDisbursementApprovalPolicy replay =
                database.budgetDisbursementApprovalPolicy(
                        request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            requirePayload(replay, request);
            return toPolicy(replay);
        }
        if (request.effectiveAt().toEpochMilli() <= clock.millis()) {
            throw new IllegalArgumentException(
                    "Budget disbursement approval policy must take effect in the future");
        }
        return toPolicy(database.scheduleBudgetDisbursementApprovalPolicy(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.nationId().value(),
                request.actorPlayerId(),
                request.tiers().stream()
                        .map(tier -> new StoredBudgetDisbursementApprovalTier(
                                tier.minimumAmount().minorUnits(),
                                tier.requiredApprovals()))
                        .toList(),
                request.approvalLifetime().toMillis(),
                request.effectiveAt().toEpochMilli(),
                request.reason(),
                clock.millis()));
    }

    public BudgetDisbursementApprovalPolicyVersion current(
            NationId nationId, Instant asOf) {
        StoredBudgetDisbursementApprovalPolicy stored =
                database.currentBudgetDisbursementApprovalPolicy(
                        nationId.value(), asOf.toEpochMilli());
        return stored == null
                ? BudgetDisbursementApprovalPolicyVersion.defaultPolicy(nationId)
                : toPolicy(stored);
    }

    public List<BudgetDisbursementApprovalPolicyVersion> history(NationId nationId) {
        if (nationId == null) {
            throw new IllegalArgumentException(
                    "Budget Disbursement Approval Policy Nation cannot be null");
        }
        return database.budgetDisbursementApprovalPolicies(nationId.value()).stream()
                .map(BudgetDisbursementApprovalPolicyRegistry::toPolicy)
                .toList();
    }

    private static void requirePayload(
            StoredBudgetDisbursementApprovalPolicy stored,
            ScheduleBudgetDisbursementApprovalPolicy request) {
        var tiers = request.tiers().stream()
                .map(tier -> new StoredBudgetDisbursementApprovalTier(
                        tier.minimumAmount().minorUnits(), tier.requiredApprovals()))
                .toList();
        if (!stored.nationId().equals(request.nationId().value())
                || !stored.actorPlayerId().equals(request.actorPlayerId())
                || !stored.tiers().equals(tiers)
                || stored.approvalLifetimeMillis() != request.approvalLifetime().toMillis()
                || stored.effectiveAtEpochMillis() != request.effectiveAt().toEpochMilli()
                || !stored.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(
                    request.serviceIdentity(), request.requestId());
        }
    }

    private static BudgetDisbursementApprovalPolicyVersion toPolicy(
            StoredBudgetDisbursementApprovalPolicy stored) {
        return new BudgetDisbursementApprovalPolicyVersion(
                stored.policyId(),
                new NationId(stored.nationId()),
                stored.tiers().stream()
                        .map(tier -> new BudgetDisbursementApprovalTier(
                                MoneyAmount.ofMinorUnits(tier.minimumAmountMinorUnits()),
                                tier.requiredApprovals()))
                        .toList(),
                Duration.ofMillis(stored.approvalLifetimeMillis()),
                Instant.ofEpochMilli(stored.effectiveAtEpochMillis()),
                stored.actorPlayerId(),
                stored.reason(),
                Instant.ofEpochMilli(stored.recordedAtEpochMillis()),
                false);
    }
}
