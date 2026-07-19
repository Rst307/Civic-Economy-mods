package org.civiceconomy.nation;

public final class UnknownNationException extends IllegalArgumentException {
    public UnknownNationException(NationId nationId) {
        super("Unknown Nation " + nationId.value());
    }
}
