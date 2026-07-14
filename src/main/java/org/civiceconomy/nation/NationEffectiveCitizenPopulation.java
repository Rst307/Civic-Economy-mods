package org.civiceconomy.nation;

import java.time.Instant;
import java.util.List;

public record NationEffectiveCitizenPopulation(
        NationId nationId,
        Instant asOf,
        List<EffectiveCitizenContribution> citizens) {
    public NationEffectiveCitizenPopulation {
        if (nationId == null || asOf == null || citizens == null) {
            throw new IllegalArgumentException("Nation Effective Citizen population cannot contain null values");
        }
        citizens = List.copyOf(citizens);
        if (citizens.stream().anyMatch(contribution -> !contribution.nationId().equals(nationId))) {
            throw new IllegalArgumentException(
                    "Every Effective Citizen contribution must belong to the same Nation");
        }
    }

    public int effectiveCitizenCount() {
        return (int) citizens.stream()
                .filter(contribution -> contribution.contribution() > 0D)
                .count();
    }

    public double populationEquivalent() {
        return citizens.stream()
                .mapToDouble(EffectiveCitizenContribution::contribution)
                .sum();
    }
}
