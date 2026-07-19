package org.civiceconomy.fiscal;

import java.util.List;
import java.util.UUID;
import org.civiceconomy.nation.NationFiscalAuthorityRegistry;
import org.civiceconomy.nation.NationFiscalPermission;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.nation.NationProvider;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredFiscalBill;

public final class NationFiscalBillInspection {
    private final CivicDatabase database;
    private final NationProvider nations;
    private final NationFiscalAuthorityRegistry authorities;

    public NationFiscalBillInspection(
            CivicDatabase database,
            NationProvider nations,
            NationFiscalAuthorityRegistry authorities) {
        if (database == null || nations == null || authorities == null) {
            throw new IllegalArgumentException(
                    "Nation Fiscal Bill inspection dependencies cannot be null");
        }
        this.database = database;
        this.nations = nations;
        this.authorities = authorities;
    }

    public List<FiscalBill> list(UUID actorPlayerId) {
        return database.fiscalBillsForBeneficiary(
                        treasuryAccount(authorizedNation(actorPlayerId))).stream()
                .map(FiscalLedger::toFiscalBill)
                .toList();
    }

    public FiscalBill status(UUID actorPlayerId, UUID billId) {
        if (billId == null) {
            throw new IllegalArgumentException("Fiscal Bill ID cannot be null");
        }
        StoredFiscalBill bill = database.fiscalBillForBeneficiary(
                billId, treasuryAccount(authorizedNation(actorPlayerId)));
        if (bill == null) {
            throw new SecurityException("Fiscal Bill is not visible to this Nation");
        }
        return FiscalLedger.toFiscalBill(bill);
    }

    private NationId authorizedNation(UUID actorPlayerId) {
        if (actorPlayerId == null) {
            throw new IllegalArgumentException("Fiscal Bill inspection actor cannot be null");
        }
        NationId nationId = nations.findForCitizen(actorPlayerId)
                .orElseThrow(() -> new SecurityException(
                        "Fiscal Bill Nation inspection requires effective Citizenship"))
                .nationId();
        authorities.require(
                nationId, actorPlayerId, NationFiscalPermission.VIEW_ACCOUNT);
        return nationId;
    }

    private static String treasuryAccount(NationId nationId) {
        return "nation:" + nationId.value() + ":treasury";
    }
}
