package org.civiceconomy.fiscal;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredFiscalBill;

public final class FiscalBillExpiryProcessor {
    public static final ServiceIdentity SERVICE_IDENTITY =
            new ServiceIdentity("civiceconomy-server");

    private final CivicDatabase database;
    private final Clock clock;

    public FiscalBillExpiryProcessor(CivicDatabase database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    public List<FiscalBill> expireDue() {
        long now = clock.millis();
        List<FiscalBill> expired = new ArrayList<>();
        for (StoredFiscalBill bill : database.dueUnfundedFiscalBills(now)) {
            String requestId = "automatic-expiry:" + bill.billId();
            UUID expiryId = UUID.nameUUIDFromBytes(
                    ("fiscal-bill-expiry:" + bill.billId())
                            .getBytes(StandardCharsets.UTF_8));
            try {
                expired.add(FiscalLedger.toFiscalBill(database.expireFiscalBill(
                        expiryId,
                        bill.billId(),
                        SERVICE_IDENTITY.value(),
                        requestId,
                        now)));
            } catch (IllegalStateException failure) {
                StoredFiscalBill current = database.fiscalBill(bill.billId());
                if (current == null
                        || ("ISSUED".equals(current.state()) && current.escrowId() == null)) {
                    throw failure;
                }
                // Another durable action owns the changed Bill. Continue the sweep.
            }
        }
        return List.copyOf(expired);
    }
}
