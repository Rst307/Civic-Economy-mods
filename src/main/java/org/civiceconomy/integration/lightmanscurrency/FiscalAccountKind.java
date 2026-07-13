package org.civiceconomy.integration.lightmanscurrency;

import org.civiceconomy.fiscal.AccountId;

public enum FiscalAccountKind {
    NATIONAL_TREASURY("nation:", ":treasury"),
    ORGANIZATION_FISCAL_ACCOUNT("organization:", ":fiscal");

    private final String prefix;
    private final String suffix;

    FiscalAccountKind(String prefix, String suffix) {
        this.prefix = prefix;
        this.suffix = suffix;
    }

    void requireMatches(AccountId accountId) {
        String value = accountId.value();
        if (!value.startsWith(prefix)
                || !value.endsWith(suffix)
                || value.length() <= prefix.length() + suffix.length()) {
            throw new IllegalArgumentException(accountId + " is not a " + name());
        }
    }
}
