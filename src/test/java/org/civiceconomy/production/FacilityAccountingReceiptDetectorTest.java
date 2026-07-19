package org.civiceconomy.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class FacilityAccountingReceiptDetectorTest {
    @Test
    void reportsOnlyNetInventoryAdditionsAndIgnoresInternalMoves() {
        FacilityAccountingReceiptDetector detector =
                new FacilityAccountingReceiptDetector();
        ProductionStack flour = stack("create:wheat_flour", 4);

        assertTrue(detector.detect(
                        List.of(flour, ProductionStack.EMPTY),
                        List.of(ProductionStack.EMPTY, flour))
                .isEmpty());

        List<MachineInventoryChange> additions = detector.detect(
                        List.of(stack("create:wheat_flour", 2), ProductionStack.EMPTY),
                        List.of(ProductionStack.EMPTY, stack("create:wheat_flour", 5)))
                .orElseThrow();

        assertEquals(1, additions.size());
        assertEquals(1, additions.getFirst().slot());
        assertEquals(3, additions.getFirst().stack().count());
    }

    private static ProductionStack stack(String itemId, int count) {
        return new ProductionStack(itemId, "components:{}", count);
    }
}
