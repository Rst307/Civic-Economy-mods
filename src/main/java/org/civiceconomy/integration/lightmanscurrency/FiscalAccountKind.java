package org.civiceconomy.integration.lightmanscurrency;

import org.civiceconomy.fiscal.AccountId;

public enum FiscalAccountKind {
    NATIONAL_TREASURY("nation:", ":treasury"),
    ORGANIZATION_FISCAL_ACCOUNT("organization:", ":fiscal"),
    TERRITORY_PREPAYMENT_CLEARING("system:territory:prepayment-clearing");

    private final String prefix;
    private final String suffix;
    private final String exactAccountId;

    FiscalAccountKind(String prefix, String suffix) {
        this.prefix = prefix;
        this.suffix = suffix;
        this.exactAccountId = null;
    }

    FiscalAccountKind(String exactAccountId) {
        this.prefix = null;
        this.suffix = null;
        this.exactAccountId = exactAccountId;
    }

    void requireMatches(AccountId accountId) {
        String value = accountId.value();
        if (exactAccountId != null) {
            if (!exactAccountId.equals(value)) {
                throw new IllegalArgumentException(accountId + " is not a " + name());
            }
            return;
        }
        if (!value.startsWith(prefix)
                || !value.endsWith(suffix)
                || value.length() <= prefix.length() + suffix.length()) {
            throw new IllegalArgumentException(accountId + " is not a " + name());
        }
    }
}
