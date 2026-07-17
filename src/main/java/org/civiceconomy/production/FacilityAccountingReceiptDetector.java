package org.civiceconomy.production;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class FacilityAccountingReceiptDetector {
    public Optional<List<MachineInventoryChange>> detect(
            List<ProductionStack> before,
            List<ProductionStack> after) {
        if (before == null || after == null || before.size() != after.size()
                || before.stream().anyMatch(java.util.Objects::isNull)
                || after.stream().anyMatch(java.util.Objects::isNull)) {
            return Optional.empty();
        }
        Map<StackIdentity, Long> remainingAdditions = totals(after);
        totals(before).forEach((identity, count) ->
                remainingAdditions.merge(identity, -count, Math::addExact));
        remainingAdditions.entrySet().removeIf(entry -> entry.getValue() <= 0L);

        List<MachineInventoryChange> additions = new ArrayList<>();
        for (int slot = 0; slot < after.size(); slot++) {
            ProductionStack current = after.get(slot);
            if (current.isEmpty()) {
                continue;
            }
            StackIdentity identity = StackIdentity.of(current);
            long remaining = remainingAdditions.getOrDefault(identity, 0L);
            if (remaining <= 0L) {
                continue;
            }
            ProductionStack previous = before.get(slot);
            int previousCount = previous.sameIdentity(current) ? previous.count() : 0;
            int localIncrease = current.count() - previousCount;
            if (localIncrease <= 0) {
                continue;
            }
            int received = (int) Math.min((long) localIncrease, remaining);
            additions.add(new MachineInventoryChange(slot, current.withCount(received)));
            remainingAdditions.put(identity, remaining - received);
        }
        return additions.isEmpty()
                ? Optional.empty()
                : Optional.of(List.copyOf(additions));
    }

    private static Map<StackIdentity, Long> totals(List<ProductionStack> stacks) {
        Map<StackIdentity, Long> totals = new HashMap<>();
        for (ProductionStack stack : stacks) {
            if (!stack.isEmpty()) {
                totals.merge(StackIdentity.of(stack), (long) stack.count(), Math::addExact);
            }
        }
        return totals;
    }

    private record StackIdentity(String itemId, String componentFingerprint) {
        private static StackIdentity of(ProductionStack stack) {
            return new StackIdentity(stack.itemId(), stack.componentFingerprint());
        }
    }
}
