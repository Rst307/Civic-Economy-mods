package org.civiceconomy.platform.neoforge;

import java.util.Arrays;
import java.util.List;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.WithdrawalApprovalTier;

public final class WithdrawalApprovalTierParser {
    private WithdrawalApprovalTierParser() {}

    public static List<WithdrawalApprovalTier> parse(String specification) {
        List<WithdrawalApprovalTier> tiers = Arrays.stream(specification.split(",", -1))
                .map(WithdrawalApprovalTierParser::parseTier)
                .toList();
        if (!tiers.getFirst().minimumAmount().equals(MoneyAmount.ZERO)) {
            throw new IllegalArgumentException(
                    "Withdrawal approval policy must start at zero");
        }
        long previous = -1L;
        for (WithdrawalApprovalTier tier : tiers) {
            long minimum = tier.minimumAmount().minorUnits();
            if (minimum <= previous) {
                throw new IllegalArgumentException(
                        "Withdrawal approval tiers must have strictly increasing amounts");
            }
            previous = minimum;
        }
        return tiers;
    }

    private static WithdrawalApprovalTier parseTier(String entry) {
        String[] parts = entry.split(":", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "Withdrawal approval tier must use <minimumAmount>:<requiredApprovals>");
        }
        return new WithdrawalApprovalTier(
                MoneyAmount.ofMinorUnits(Long.parseLong(parts[0])),
                Integer.parseInt(parts[1]));
    }
}
