package org.civiceconomy.platform.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.civiceconomy.fiscal.BudgetDisbursementApprovalTier;
import org.civiceconomy.fiscal.MoneyAmount;
import org.junit.jupiter.api.Test;

class BudgetDisbursementApprovalTierParserTest {
    @Test
    void parsesOrderedMultiTierSpecification() {
        assertEquals(
                List.of(
                        new BudgetDisbursementApprovalTier(MoneyAmount.ZERO, 1),
                        new BudgetDisbursementApprovalTier(
                                MoneyAmount.ofMinorUnits(500L), 2),
                        new BudgetDisbursementApprovalTier(
                                MoneyAmount.ofMinorUnits(2_000L), 3)),
                BudgetDisbursementApprovalTierParser.parse("0:1,500:2,2000:3"));
    }

    @Test
    void rejectsMalformedOrUnsafeTiers() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BudgetDisbursementApprovalTierParser.parse("500:2"));
        assertThrows(
                IllegalArgumentException.class,
                () -> BudgetDisbursementApprovalTierParser.parse("0:1,500:2,500:3"));
        assertThrows(
                IllegalArgumentException.class,
                () -> BudgetDisbursementApprovalTierParser.parse("0:17"));
        assertThrows(
                IllegalArgumentException.class,
                () -> BudgetDisbursementApprovalTierParser.parse("0:1,"));
        assertThrows(
                IllegalArgumentException.class,
                () -> BudgetDisbursementApprovalTierParser.parse(
                        "0:1,9223372036854775808:2"));
    }
}
