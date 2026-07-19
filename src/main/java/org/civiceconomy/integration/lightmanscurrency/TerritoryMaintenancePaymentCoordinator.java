package org.civiceconomy.integration.lightmanscurrency;

import java.time.Clock;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import org.civiceconomy.fiscal.FailurePoint;
import org.civiceconomy.fiscal.FiscalLedger;
import org.civiceconomy.fiscal.FiscalServiceSession;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.PaymentCoordinator;
import org.civiceconomy.fiscal.PaymentTransaction;
import org.civiceconomy.fiscal.ReleaseReservation;
import org.civiceconomy.fiscal.Reservation;
import org.civiceconomy.fiscal.ReserveFunds;
import org.civiceconomy.fiscal.SettleReservation;
import org.civiceconomy.monetary.ConfirmPermanentDestruction;
import org.civiceconomy.monetary.MonetarySupplyEvent;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.territory.ChargeTerritoryMaintenance;
import org.civiceconomy.territory.ConfirmTerritoryMaintenanceSettlement;
import org.civiceconomy.territory.SettleAvailableTerritoryMaintenance;
import org.civiceconomy.territory.SuspendTerritoryMaintenance;
import org.civiceconomy.territory.TerritoryMaintenancePriorityPolicy;
import org.civiceconomy.territory.TerritoryMaintenanceRegistry;
import org.civiceconomy.territory.TerritoryMaintenancePayment;
import org.civiceconomy.territory.TerritoryMaintenanceSettlementOutcome;
import org.civiceconomy.territory.TerritoryMaintenanceSettlementResult;

public final class TerritoryMaintenancePaymentCoordinator {
    private final CivicDatabase database;
    private final FiscalLedger ledger;
    private final PaymentCoordinator payments;
    private final PermanentDestructionCoordinator destructions;
    private final TerritoryMaintenanceRegistry maintenance;

    TerritoryMaintenancePaymentCoordinator(
            CivicDatabase database,
            FiscalLedger ledger,
            PaymentCoordinator payments,
            PermanentDestructionCoordinator destructions,
            TerritoryMaintenanceRegistry maintenance) {
        if (database == null
                || ledger == null
                || payments == null
                || destructions == null
                || maintenance == null) {
            throw new IllegalArgumentException("Territory maintenance payment dependencies cannot be null");
        }
        this.database = database;
        this.ledger = ledger;
        this.payments = payments;
        this.destructions = destructions;
        this.maintenance = maintenance;
    }

    public static TerritoryMaintenancePaymentCoordinator live(
            CivicDatabase database,
            FiscalServiceSession session,
            Clock clock,
            ServerLevel level) {
        return new TerritoryMaintenancePaymentCoordinator(
                database,
                FiscalLedger.authorized(
                        database,
                        LightmansCurrencyAccountBalances.live(level),
                        session),
                PaymentCoordinator.authorized(
                        database,
                        LightmansCurrencyPayments.live(level),
                        session),
                PermanentDestructionCoordinator.live(database, session, clock, level),
                new TerritoryMaintenanceRegistry(database, clock));
    }

    public TerritoryMaintenancePayment charge(ChargeTerritoryMaintenance request) {
        long assessedDue = database.territoryMaintenanceDueMinorUnits(
                request.cycleId(), request.nationId().value());
        if (assessedDue != request.totalDue().minorUnits()) {
            throw new IllegalArgumentException(
                    "Territory maintenance total must match exact cycle assessments");
        }
        return chargeExact(request);
    }

    public TerritoryMaintenanceSettlementResult settleAvailable(
            SettleAvailableTerritoryMaintenance request) {
        String settlementRequestId = request.requestId() + ":settlement";
        var replay = database.territoryMaintenanceSettlement(
                request.serviceIdentity().value(), settlementRequestId);
        if (replay != null) {
            if (replay.outcome().equals(TerritoryMaintenanceSettlementOutcome.UNFUNDED.name())) {
                var settlement = maintenance.suspend(new SuspendTerritoryMaintenance(
                        request.serviceIdentity(),
                        settlementRequestId,
                        request.cycleId(),
                        request.nationId(),
                        request.reason()));
                return new TerritoryMaintenanceSettlementResult(Optional.empty(), settlement);
            }
            if (replay.reservationId() == null) {
                var settlement = maintenance.settleZeroCostAssessments(
                        new SuspendTerritoryMaintenance(
                                request.serviceIdentity(),
                                settlementRequestId,
                                request.cycleId(),
                                request.nationId(),
                                request.reason()));
                return new TerritoryMaintenanceSettlementResult(Optional.empty(), settlement);
            }
            var reservation = database.reservationRecord(replay.reservationId());
            if (reservation == null) {
                throw new IllegalStateException(
                        "Territory Maintenance Settlement is missing its Reservation");
            }
            TerritoryMaintenancePayment payment = chargeExact(toCharge(
                    request, MoneyAmount.ofMinorUnits(reservation.amountMinorUnits())));
            return new TerritoryMaintenanceSettlementResult(Optional.of(payment), payment.settlement());
        }

        var candidates = maintenance.pendingCandidates(request.cycleId(), request.nationId());
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "Territory Maintenance settlement requires pending Assessments");
        }
        MoneyAmount available = ledger.availableBalance(
                request.serviceIdentity(), request.treasuryAccount());
        var decision = new TerritoryMaintenancePriorityPolicy().select(candidates, available);
        if (decision.fundedAmount().equals(MoneyAmount.ZERO)
                && !decision.funded().isEmpty()) {
            var settlement = maintenance.settleZeroCostAssessments(
                    new SuspendTerritoryMaintenance(
                            request.serviceIdentity(),
                            settlementRequestId,
                            request.cycleId(),
                            request.nationId(),
                            request.reason()));
            return new TerritoryMaintenanceSettlementResult(Optional.empty(), settlement);
        }
        if (decision.fundedAmount().equals(MoneyAmount.ZERO)) {
            var settlement = maintenance.suspend(new SuspendTerritoryMaintenance(
                    request.serviceIdentity(),
                    settlementRequestId,
                    request.cycleId(),
                    request.nationId(),
                    request.reason()));
            return new TerritoryMaintenanceSettlementResult(Optional.empty(), settlement);
        }
        TerritoryMaintenancePayment payment = chargeExact(toCharge(request, decision.fundedAmount()));
        return new TerritoryMaintenanceSettlementResult(Optional.of(payment), payment.settlement());
    }

    private TerritoryMaintenancePayment chargeExact(ChargeTerritoryMaintenance request) {
        Split split = split(request.totalDue(), request.destructionBasisPoints());
        String purpose = "Territory Maintenance "
                + request.cycleId()
                + " for Nation "
                + request.nationId().value()
                + ": "
                + request.reason();
        Reservation reservation = ledger.reserve(new ReserveFunds(
                request.serviceIdentity(),
                request.requestId() + ":reserve",
                request.treasuryAccount(),
                request.totalDue(),
                purpose));
        PaymentTransaction publicFundPayment = payments.settle(
                new SettleReservation(
                        request.serviceIdentity(),
                        request.requestId() + ":public-fund",
                        reservation.reservationId(),
                        LightmansCurrencyPublicMaintenanceFundProvisioner.ACCOUNT_ID,
                        split.publicFundAmount()),
                FailurePoint.NONE);
        MonetarySupplyEvent destruction = null;
        if (!split.destroyedAmount().equals(MoneyAmount.ZERO)) {
            destruction = destructions.confirm(new ConfirmPermanentDestruction(
                    request.serviceIdentity(),
                    request.requestId() + ":destruction",
                    request.treasuryAccount(),
                    split.destroyedAmount(),
                    purpose));
        }
        if (!split.destroyedAmount().equals(MoneyAmount.ZERO)) {
            ledger.release(new ReleaseReservation(
                    request.serviceIdentity(),
                    request.requestId() + ":release",
                    reservation.reservationId(),
                    "Release externally destroyed maintenance share"));
        }
        var settlement = maintenance.confirmSettlement(new ConfirmTerritoryMaintenanceSettlement(
                        request.serviceIdentity(),
                        request.requestId() + ":settlement",
                        request.cycleId(),
                        request.nationId(),
                        reservation.reservationId(),
                        publicFundPayment.transactionId(),
                        destruction == null ? null : destructionOperationId(destruction),
                        request.reason()));
        return new TerritoryMaintenancePayment(
                reservation,
                publicFundPayment,
                Optional.ofNullable(destruction),
                split.publicFundAmount(),
                split.destroyedAmount(),
                settlement);
    }

    private static ChargeTerritoryMaintenance toCharge(
            SettleAvailableTerritoryMaintenance request, MoneyAmount amount) {
        return new ChargeTerritoryMaintenance(
                request.serviceIdentity(),
                request.requestId(),
                request.cycleId(),
                request.nationId(),
                request.treasuryAccount(),
                amount,
                request.destructionBasisPoints(),
                request.reason());
    }

    public void recoverPermanentDestructions() {
        destructions.recoverAll();
    }

    static Split split(MoneyAmount totalDue, int destructionBasisPoints) {
        if (totalDue == null
                || destructionBasisPoints < 3_000
                || destructionBasisPoints > 8_000) {
            throw new IllegalArgumentException("Territory maintenance split values are invalid");
        }
        long destroyed = Math.multiplyExact(totalDue.minorUnits(), destructionBasisPoints) / 10_000L;
        MoneyAmount destroyedAmount = MoneyAmount.ofMinorUnits(destroyed);
        return new Split(totalDue.minus(destroyedAmount), destroyedAmount);
    }

    private static java.util.UUID destructionOperationId(MonetarySupplyEvent destruction) {
        String prefix = "permanent-destruction:";
        if (!destruction.externalReference().startsWith(prefix)) {
            throw new IllegalStateException(
                    "Territory maintenance destruction has an invalid external reference");
        }
        return java.util.UUID.fromString(destruction.externalReference().substring(prefix.length()));
    }

    record Split(MoneyAmount publicFundAmount, MoneyAmount destroyedAmount) {}
}
