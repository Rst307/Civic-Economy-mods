package org.civiceconomy.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MillstoneCompletionDetectorTest {
    @Test
    void detectsOneActualConsumedInputAndExactInventoryOutputDelta() {
        ProductionStack inputBefore = stack("minecraft:wheat", 3);
        ProductionStack inputAfter = stack("minecraft:wheat", 2);
        List<ProductionStack> outputBefore = List.of(
                ProductionStack.EMPTY, stack("minecraft:wheat_seeds", 1));
        List<ProductionStack> outputAfter = List.of(
                stack("minecraft:string", 2), stack("minecraft:wheat_seeds", 2));

        MachineInventoryDelta delta = new MillstoneCompletionDetector()
                .detect(inputBefore, inputAfter, outputBefore, outputAfter)
                .orElseThrow();

        assertEquals(1, delta.inputs().size());
        assertEquals("minecraft:wheat", delta.inputs().getFirst().stack().itemId());
        assertEquals(1, delta.inputs().getFirst().stack().count());
        assertEquals(2, delta.outputs().size());
        assertEquals(3, delta.outputs().stream()
                .mapToInt(change -> change.stack().count())
                .sum());
    }

    @Test
    void ignoresNoOpInputOnlyAndOutputOnlyMutations() {
        MillstoneCompletionDetector detector = new MillstoneCompletionDetector();
        ProductionStack wheat = stack("minecraft:wheat", 1);

        assertTrue(detector.detect(
                        wheat, wheat, List.of(ProductionStack.EMPTY), List.of(ProductionStack.EMPTY))
                .isEmpty());
        assertTrue(detector.detect(
                        wheat,
                        ProductionStack.EMPTY,
                        List.of(ProductionStack.EMPTY),
                        List.of(ProductionStack.EMPTY))
                .isEmpty());
        assertTrue(detector.detect(
                        wheat,
                        wheat,
                        List.of(ProductionStack.EMPTY),
                        List.of(stack("minecraft:string", 1)))
                .isEmpty());
    }

    @Test
    void failsClosedWhenInputIdentityOrOutputInventoryShapeChanges() {
        MillstoneCompletionDetector detector = new MillstoneCompletionDetector();

        assertTrue(detector.detect(
                        stack("minecraft:wheat", 2),
                        stack("minecraft:carrot", 1),
                        List.of(ProductionStack.EMPTY),
                        List.of(stack("minecraft:string", 1)))
                .isEmpty());
        assertTrue(detector.detect(
                        stack("minecraft:wheat", 2),
                        stack("minecraft:wheat", 1),
                        List.of(ProductionStack.EMPTY),
                        List.of(ProductionStack.EMPTY, ProductionStack.EMPTY))
                .isEmpty());
    }

    private static ProductionStack stack(String itemId, int count) {
        return new ProductionStack(itemId, "components:{}", count);
    }
}
