package org.civiceconomy.strength;

import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.FiscalAuthorization;
import org.civiceconomy.fiscal.FiscalCapability;
import org.civiceconomy.fiscal.FiscalServiceSession;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredAuditableEconomicActivityEvidence;

public final class AuditableEconomicActivityRegistry {
    private final CivicDatabase database;
    private final FiscalAuthorization authorization;
    private final FiscalServiceSession session;

    public AuditableEconomicActivityRegistry(
            CivicDatabase database, FiscalServiceSession session) {
        if (database == null || session == null) {
            throw new IllegalArgumentException(
                    "Auditable Economic Activity registry dependencies cannot be null");
        }
        this.database = database;
        this.authorization = new FiscalAuthorization(database);
        this.session = session;
    }

    public AuditableEconomicActivityAssessment register(
            RegisterAuditableEconomicActivityEvidence request) {
        AccountId treasury = new AccountId(
                "nation:" + request.nationId().value() + ":treasury");
        authorization.require(
                session,
                request.serviceIdentity(),
                FiscalCapability.RECORD_ECONOMIC_ACTIVITY,
                treasury);
        return toAssessment(database.registerAuditableEconomicActivityEvidence(
                request.serviceIdentity().value(),
                request.requestId(),
                request.paymentTransactionId(),
                request.nationId().value(),
                request.subject().name(),
                request.subjectReference(),
                request.payerController(),
                request.recipientController(),
                request.referenceValueMinorUnits(),
                request.evidenceReference(),
                request.recordedAtEpochMillis()));
    }

    private static AuditableEconomicActivityAssessment toAssessment(
            StoredAuditableEconomicActivityEvidence stored) {
        return new AuditableEconomicActivityAssessment(
                new NationId(stored.nationId()),
                stored.paymentTransactionId(),
                AuditableEconomicActivityDecision.valueOf(stored.decision()),
                MoneyAmount.ofMinorUnits(stored.includedValueMinorUnits()),
                AuditableEconomicActivitySubject.valueOf(stored.subject()),
                stored.subjectReference(),
                stored.evidenceReference());
    }
}
