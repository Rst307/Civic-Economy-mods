package org.civiceconomy.platform.neoforge;

import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import org.civiceconomy.fiscal.MoneyAmount;

final class ApprovalTierParser {
    private ApprovalTierParser() {}

    static <T> List<T> parse(
            String specification,
            BiFunction<MoneyAmount, Integer, T> tierFactory) {
        if (specification == null || tierFactory == null) {
            throw new IllegalArgumentException(
                    "Approval tier specification and factory cannot be null");
        }
        List<ParsedTier> parsed = Arrays.stream(specification.split(",", -1))
                .map(ApprovalTierParser::parseTier)
                .toList();
        if (!parsed.getFirst().minimumAmount().equals(MoneyAmount.ZERO)) {
            throw new IllegalArgumentException(
                    "Approval policy must start at zero");
        }
        long previous = -1L;
        for (ParsedTier tier : parsed) {
            long minimum = tier.minimumAmount().minorUnits();
            if (minimum <= previous) {
                throw new IllegalArgumentException(
                        "Approval tiers must have strictly increasing amounts");
            }
            previous = minimum;
        }
        return parsed.stream()
                .map(tier -> tierFactory.apply(
                        tier.minimumAmount(), tier.requiredApprovals()))
                .toList();
    }

    private static ParsedTier parseTier(String entry) {
        String[] parts = entry.split(":", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "Approval tier must use <minimumAmount>:<requiredApprovals>");
        }
        int requiredApprovals = Integer.parseInt(parts[1]);
        if (requiredApprovals < 1 || requiredApprovals > 16) {
            throw new IllegalArgumentException(
                    "Approval tier requires between 1 and 16 approvals");
        }
        return new ParsedTier(
                MoneyAmount.ofMinorUnits(Long.parseLong(parts[0])),
                requiredApprovals);
    }

    private record ParsedTier(
            MoneyAmount minimumAmount,
            int requiredApprovals) {}
}
