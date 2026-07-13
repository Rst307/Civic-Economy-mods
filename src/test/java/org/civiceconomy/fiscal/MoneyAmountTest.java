package org.civiceconomy.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MoneyAmountTest {
    @Test
    void amountsUseNonnegativeLcMinorUnitsAndCheckedArithmetic() {
        MoneyAmount twelve = MoneyAmount.ofMinorUnits(12);
        MoneyAmount thirty = MoneyAmount.ofMinorUnits(30);

        assertEquals(MoneyAmount.ofMinorUnits(42), twelve.plus(thirty));
        assertEquals(MoneyAmount.ofMinorUnits(18), thirty.minus(twelve));
        assertThrows(IllegalArgumentException.class, () -> MoneyAmount.ofMinorUnits(-1));
        assertThrows(ArithmeticException.class, () -> MoneyAmount.ofMinorUnits(Long.MAX_VALUE).plus(twelve));
        assertThrows(IllegalArgumentException.class, () -> twelve.minus(thirty));
    }
}
