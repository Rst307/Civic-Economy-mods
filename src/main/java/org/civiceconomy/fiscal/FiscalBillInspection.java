package org.civiceconomy.fiscal;

import java.util.List;
import java.util.UUID;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredFiscalBill;

public final class FiscalBillInspection {
    private final CivicDatabase database;

    public FiscalBillInspection(CivicDatabase database) {
        if (database == null) {
            throw new IllegalArgumentException("Fiscal Bill inspection database cannot be null");
        }
        this.database = database;
    }

    public List<FiscalBill> listForPayer(UUID actorPlayerId) {
        return database.fiscalBillsForPayer(payerAccount(actorPlayerId)).stream()
                .map(FiscalLedger::toFiscalBill)
                .toList();
    }

    public FiscalBill statusForPayer(UUID actorPlayerId, UUID billId) {
        if (billId == null) {
            throw new IllegalArgumentException("Fiscal Bill ID cannot be null");
        }
        StoredFiscalBill bill = database.fiscalBillForPayer(
                billId, payerAccount(actorPlayerId));
        if (bill == null) {
            throw new SecurityException("Fiscal Bill is not visible to this payer");
        }
        return FiscalLedger.toFiscalBill(bill);
    }

    private static String payerAccount(UUID actorPlayerId) {
        if (actorPlayerId == null) {
            throw new IllegalArgumentException("Fiscal Bill inspection actor cannot be null");
        }
        return "player:" + actorPlayerId;
    }
}
