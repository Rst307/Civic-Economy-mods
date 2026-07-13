package org.civiceconomy.fiscal;

public record AccountId(String value) {
    public AccountId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Account ID cannot be blank");
        }
    }
}
