package org.civiceconomy.platform.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.WithdrawalApprovalTier;
import org.junit.jupiter.api.Test;

class WithdrawalApprovalTierParserTest {
    @Test
    void parsesOrderedMultiTierSpecification() {
        assertEquals(
                List.of(
                        new WithdrawalApprovalTier(MoneyAmount.ZERO, 1),
                        new WithdrawalApprovalTier(MoneyAmount.ofMinorUnits(500L), 2),
                        new WithdrawalApprovalTier(MoneyAmount.ofMinorUnits(2_000L), 3)),
                WithdrawalApprovalTierParser.parse("0:1,500:2,2000:3"));
    }

    @Test
    void rejectsSpecificationWithoutZeroTier() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WithdrawalApprovalTierParser.parse("500:2"));
    }

    @Test
    void rejectsNonIncreasingTierAmounts() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WithdrawalApprovalTierParser.parse("0:1,500:2,500:3"));
        assertThrows(
                IllegalArgumentException.class,
                () -> WithdrawalApprovalTierParser.parse("0:1,500:2,200:3"));
    }

    @Test
    void rejectsApprovalCountsOutsideSupportedRange() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WithdrawalApprovalTierParser.parse("0:0"));
        assertThrows(
                IllegalArgumentException.class,
                () -> WithdrawalApprovalTierParser.parse("0:17"));
    }

    @Test
    void rejectsMalformedTierEntries() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WithdrawalApprovalTierParser.parse("0:1:2"));
        assertThrows(
                IllegalArgumentException.class,
                () -> WithdrawalApprovalTierParser.parse("0:1,"));
    }

    @Test
    void rejectsInvalidOrOverflowingNumbers() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WithdrawalApprovalTierParser.parse("0:1,-1:2"));
        assertThrows(
                IllegalArgumentException.class,
                () -> WithdrawalApprovalTierParser.parse("0:one"));
        assertThrows(
                IllegalArgumentException.class,
                () -> WithdrawalApprovalTierParser.parse("0:1,9223372036854775808:2"));
    }
}
