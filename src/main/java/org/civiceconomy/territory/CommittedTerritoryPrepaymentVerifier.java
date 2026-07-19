package org.civiceconomy.territory;

import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredPaymentTransaction;

public final class CommittedTerritoryPrepaymentVerifier
        implements TerritoryPrepaymentVerifier {
    private final CivicDatabase database;
    private final AccountId clearingAccount;

    public CommittedTerritoryPrepaymentVerifier(
            CivicDatabase database, AccountId clearingAccount) {
        if (database == null || clearingAccount == null) {
            throw new IllegalArgumentException("Territory prepayment verifier dependencies cannot be null");
        }
        this.database = database;
        this.clearingAccount = clearingAccount;
    }

    @Override
    public boolean isCommitted(
            UUID transactionId, NationId nationId, MoneyAmount amount) {
        if (transactionId == null || nationId == null || amount == null) {
            return false;
        }
        StoredPaymentTransaction transaction = database.paymentTransaction(transactionId);
        if (transaction == null) {
            return false;
        }
        AccountId expectedTreasury =
                new AccountId("nation:" + nationId.value() + ":treasury");
        return "PAYMENT".equals(transaction.kind())
                && "CIVIC_COMMITTED".equals(transaction.state())
                && expectedTreasury.value().equals(transaction.sourceAccount())
                && clearingAccount.value().equals(transaction.recipientAccount())
                && transaction.amountMinorUnits() == amount.minorUnits()
                && transaction.refundedMinorUnits() == 0L;
    }
}
