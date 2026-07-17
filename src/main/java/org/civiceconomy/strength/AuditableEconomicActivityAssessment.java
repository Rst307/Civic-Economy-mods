package org.civiceconomy.strength;

import java.util.UUID;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.nation.NationId;

public record AuditableEconomicActivityAssessment(
        NationId nationId,
        UUID transactionId,
        AuditableEconomicActivityDecision decision,
        MoneyAmount includedValue,
        AuditableEconomicActivitySubject subject,
        String subjectReference,
        String evidenceReference) {}
