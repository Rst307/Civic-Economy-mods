package org.civiceconomy.platform.neoforge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.WithdrawalApprovalPolicyVersion;
import org.civiceconomy.fiscal.WithdrawalApprovalTier;
import org.civiceconomy.nation.NationId;
import org.junit.jupiter.api.Test;

class WithdrawalApprovalPolicyFormattingTest {
    @Test
    void statusDisplaysThePinnedApprovalLifetime() {
        var policy = new WithdrawalApprovalPolicyVersion(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                new NationId(UUID.fromString("22222222-2222-2222-2222-222222222222")),
                List.of(new WithdrawalApprovalTier(MoneyAmount.ZERO, 2)),
                Duration.ofDays(3L),
                Instant.parse("2026-07-20T00:00:00Z"),
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                "Three-day approval window",
                Instant.parse("2026-07-16T00:00:00Z"),
                false);

        assertTrue(WithdrawalApprovalPolicyFormatter.format(policy)
                .contains("approvalLifetime=PT72H"));
    }
}
