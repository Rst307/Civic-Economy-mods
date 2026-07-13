package org.civiceconomy.fiscal;

public record ServiceIdentity(String value) {
    public ServiceIdentity {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Service identity cannot be blank");
        }
    }
}
