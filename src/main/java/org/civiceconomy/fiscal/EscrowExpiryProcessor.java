package org.civiceconomy.fiscal;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.PendingReservationPaymentException;
import org.civiceconomy.persistence.StoredEscrow;

public final class EscrowExpiryProcessor {
    public static final ServiceIdentity SERVICE_IDENTITY =
            new ServiceIdentity("civiceconomy-server");

    private final CivicDatabase database;
    private final Clock clock;

    public EscrowExpiryProcessor(CivicDatabase database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    public List<Escrow> expireDue() {
        long now = clock.millis();
        List<Escrow> expired = new ArrayList<>();
        for (StoredEscrow escrow : database.dueActiveEscrows(now)) {
            String requestId = "automatic-expiry:" + escrow.escrowId();
            UUID expiryId = UUID.nameUUIDFromBytes(
                    ("escrow-expiry:" + escrow.escrowId())
                            .getBytes(StandardCharsets.UTF_8));
            try {
                expired.add(FiscalLedger.toEscrow(database.expireEscrow(
                        expiryId,
                        SERVICE_IDENTITY.value(),
                        requestId,
                        escrow.escrowId(),
                        now)));
            } catch (PendingReservationPaymentException blocked) {
                // Recovery owns the ambiguous payment. A later sweep retries this Escrow.
            }
        }
        return List.copyOf(expired);
    }
}
