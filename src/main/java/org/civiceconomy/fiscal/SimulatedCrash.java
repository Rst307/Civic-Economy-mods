package org.civiceconomy.fiscal;

public final class SimulatedCrash extends RuntimeException {
    public SimulatedCrash(FailurePoint failurePoint) {
        super("Simulated crash at " + failurePoint);
    }
}
