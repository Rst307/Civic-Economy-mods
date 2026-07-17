package org.civiceconomy.production;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class MillstoneCompletionDetector {
    public Optional<MachineInventoryDelta> detect(
            ProductionStack inputBefore,
            ProductionStack inputAfter,
            List<ProductionStack> outputBefore,
            List<ProductionStack> outputAfter) {
        if (inputBefore == null || inputAfter == null
                || outputBefore == null || outputAfter == null
                || outputBefore.size() != outputAfter.size()
                || outputBefore.stream().anyMatch(java.util.Objects::isNull)
                || outputAfter.stream().anyMatch(java.util.Objects::isNull)) {
            return Optional.empty();
        }
        if (inputBefore.isEmpty()
                || (!inputAfter.isEmpty() && !inputBefore.sameIdentity(inputAfter))
                || inputBefore.count() - inputAfter.count() != 1) {
            return Optional.empty();
        }
        List<MachineInventoryChange> outputs = new ArrayList<>();
        for (int slot = 0; slot < outputBefore.size(); slot++) {
            ProductionStack before = outputBefore.get(slot);
            ProductionStack after = outputAfter.get(slot);
            if (before.isEmpty()) {
                if (!after.isEmpty()) {
                    outputs.add(new MachineInventoryChange(slot, after));
                }
                continue;
            }
            if (after.isEmpty() || !before.sameIdentity(after)
                    || after.count() < before.count()) {
                return Optional.empty();
            }
            int added = after.count() - before.count();
            if (added > 0) {
                outputs.add(new MachineInventoryChange(slot, after.withCount(added)));
            }
        }
        if (outputs.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MachineInventoryDelta(
                List.of(new MachineInventoryChange(0, inputBefore.withCount(1))),
                outputs));
    }
}
