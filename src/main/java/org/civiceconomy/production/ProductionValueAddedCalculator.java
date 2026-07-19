package org.civiceconomy.production;

import java.time.Instant;
import java.util.Optional;

public final class ProductionValueAddedCalculator {
    private final GlobalReferencePriceRegistry prices;

    public ProductionValueAddedCalculator(GlobalReferencePriceRegistry prices) {
        if (prices == null) {
            throw new IllegalArgumentException("Production Value Added price registry is required");
        }
        this.prices = prices;
    }

    public ProductionValueAddedAssessment calculate(FacilityProductionObservation observation) {
        if (observation == null) {
            throw new IllegalArgumentException("Production observation is required");
        }
        Instant asOf = Instant.ofEpochMilli(
                observation.completion().observedAtEpochMillis());
        if (observation.decision().kind() != FacilityProductionDecisionKind.INCLUDED) {
            return excluded(
                    observation,
                    ProductionValueAddedDecision.EXCLUDED_NOT_INCLUDED,
                    "Only an INCLUDED Facility Production Decision can contribute");
        }
        Optional<Long> outputValue = valueOf(observation.receipt().receivedOutputs(), asOf);
        Optional<Long> inputValue = valueOf(observation.completion().inventoryDelta().inputs(), asOf);
        if (outputValue.isEmpty() || inputValue.isEmpty()) {
            return excluded(
                    observation,
                    ProductionValueAddedDecision.EXCLUDED_UNPRICED,
                    "Every accepted input and output must have an exact Global Reference Price");
        }
        long output = outputValue.orElseThrow();
        long input = inputValue.orElseThrow();
        if (output <= input) {
            return new ProductionValueAddedAssessment(
                    observation.completion().observationId(),
                    output,
                    input,
                    0L,
                    ProductionValueAddedDecision.EXCLUDED_NON_POSITIVE,
                    "Production Value Added is not positive");
        }
        return new ProductionValueAddedAssessment(
                observation.completion().observationId(),
                output,
                input,
                Math.subtractExact(output, input),
                ProductionValueAddedDecision.INCLUDED,
                "Accepted production output value less consumed input value");
    }

    private Optional<Long> valueOf(
            java.util.List<MachineInventoryChange> changes, Instant asOf) {
        long total = 0L;
        for (MachineInventoryChange change : changes) {
            Optional<GlobalReferencePriceVersion> price = prices.current(change.stack(), asOf);
            if (price.isEmpty()) {
                return Optional.empty();
            }
            try {
                total = Math.addExact(
                        total,
                        Math.multiplyExact(
                                price.orElseThrow().unitPriceMinorUnits(),
                                change.stack().count()));
            } catch (ArithmeticException overflow) {
                return Optional.empty();
            }
        }
        return Optional.of(total);
    }

    private static ProductionValueAddedAssessment excluded(
            FacilityProductionObservation observation,
            ProductionValueAddedDecision decision,
            String reason) {
        return new ProductionValueAddedAssessment(
                observation.completion().observationId(), 0L, 0L, 0L, decision, reason);
    }
}
