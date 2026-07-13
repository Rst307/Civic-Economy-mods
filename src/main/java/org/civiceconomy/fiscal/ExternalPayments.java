package org.civiceconomy.fiscal;

@FunctionalInterface
public interface ExternalPayments {
    void apply(ExternalPayment payment);
}
