package org.civiceconomy.fiscal;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredBudget;

public final class BudgetDraftExpiryProcessor {
    public static final ServiceIdentity SERVICE_IDENTITY =
            new ServiceIdentity("civiceconomy-server");

    private final CivicDatabase database;
    private final Clock clock;

    public BudgetDraftExpiryProcessor(CivicDatabase database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    public List<Budget> expireDue() {
        long now = clock.millis();
        List<Budget> expired = new ArrayList<>();
        for (StoredBudget budget : database.dueDraftBudgets(now)) {
            String requestId = "automatic-expiry:" + budget.budgetId();
            UUID expiryId = UUID.nameUUIDFromBytes(
                    ("budget-draft-expiry:" + budget.budgetId())
                            .getBytes(StandardCharsets.UTF_8));
            try {
                expired.add(FiscalLedger.toBudget(database.expireBudgetDraft(
                        expiryId,
                        budget.budgetId(),
                        SERVICE_IDENTITY.value(),
                        requestId,
                        now)));
            } catch (IllegalStateException failure) {
                StoredBudget current = database.budget(budget.budgetId());
                if (current == null
                        || ("DRAFT".equals(current.state()) && current.escrowId() == null)) {
                    throw failure;
                }
                // Another durable action owns the changed Budget. Continue the sweep.
            }
        }
        return List.copyOf(expired);
    }
}
