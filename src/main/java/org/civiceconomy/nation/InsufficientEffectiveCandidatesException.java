package org.civiceconomy.nation;

public final class InsufficientEffectiveCandidatesException extends IllegalStateException {
    private final int required;
    private final int actual;

    public InsufficientEffectiveCandidatesException(int required, int actual) {
        super("Nation activation requires " + required
                + " Effective Candidates but found " + actual);
        this.required = required;
        this.actual = actual;
    }

    public int required() {
        return required;
    }

    public int actual() {
        return actual;
    }
}
