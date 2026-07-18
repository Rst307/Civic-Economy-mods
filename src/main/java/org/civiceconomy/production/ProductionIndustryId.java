package org.civiceconomy.production;

public record ProductionIndustryId(String value) implements Comparable<ProductionIndustryId> {
    public ProductionIndustryId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Production Industry ID cannot be blank");
        }
    }

    @Override
    public int compareTo(ProductionIndustryId other) {
        return value.compareTo(other.value);
    }
}
