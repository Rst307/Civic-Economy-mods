package org.civiceconomy.production;

import java.util.Optional;

public record ProductionContributionBindingAssessment(
        ProductionContributionBindingDecision decision,
        ProductionValueAddedAssessment valueAdded,
        Optional<BoundProductionMarginalReturnContribution> binding,
        String reason) {
    public ProductionContributionBindingAssessment {
        if (decision == null || valueAdded == null || binding == null
                || reason == null || reason.isBlank()
                || (decision == ProductionContributionBindingDecision.BOUND)
                        != binding.isPresent()) {
            throw new IllegalArgumentException(
                    "Production contribution binding assessment is invalid");
        }
    }
}
