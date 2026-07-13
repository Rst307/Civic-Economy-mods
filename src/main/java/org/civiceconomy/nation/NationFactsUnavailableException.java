package org.civiceconomy.nation;

public final class NationFactsUnavailableException extends IllegalStateException {
    public NationFactsUnavailableException(NationId nationId) {
        super("Nation facts are unavailable for registered Nation " + nationId.value());
    }
}
