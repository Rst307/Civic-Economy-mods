package org.civiceconomy.fiscal;

import org.civiceconomy.persistence.CivicDatabase;

public final class FiscalTestSessions {
    private FiscalTestSessions() {}

    public static FiscalServiceSession open(
            CivicDatabase database, ServiceIdentity serviceIdentity, String verifiedOwnerModId) {
        return new FiscalAuthorization(database).openSession(serviceIdentity, verifiedOwnerModId);
    }
}
