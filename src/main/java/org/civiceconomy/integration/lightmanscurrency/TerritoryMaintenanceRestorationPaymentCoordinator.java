package org.civiceconomy.integration.lightmanscurrency;

import java.util.Optional;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.FailurePoint;
import org.civiceconomy.fiscal.FiscalLedger;
import org.civiceconomy.fiscal.PaymentCoordinator;
import org.civiceconomy.fiscal.ReleaseReservation;
import org.civiceconomy.fiscal.Reservation;
import org.civiceconomy.fiscal.ReserveFunds;
import org.civiceconomy.fiscal.SettleReservation;
import org.civiceconomy.monetary.ConfirmPermanentDestruction;
import org.civiceconomy.monetary.MonetarySupplyEvent;
import org.civiceconomy.nation.NationFiscalAuthorityRegistry;
import org.civiceconomy.nation.NationFiscalPermission;
import org.civiceconomy.nation.NationRegistry;
import org.civiceconomy.territory.ConfirmTerritoryMaintenanceRestoration;
import org.civiceconomy.territory.PrepareTerritoryMaintenanceRestoration;
import org.civiceconomy.territory.TerritoryMaintenanceRestoration;
import org.civiceconomy.territory.TerritoryMaintenanceRestorationPayment;
import org.civiceconomy.territory.TerritoryMaintenanceRestorationRegistry;
import org.civiceconomy.territory.TerritoryMaintenanceRestorationState;

public final class TerritoryMaintenanceRestorationPaymentCoordinator {
    private final NationRegistry nations;
    private final NationFiscalAuthorityRegistry authorities;
    private final FiscalLedger ledger;
    private final PaymentCoordinator payments;
    private final PermanentDestructionCoordinator destructions;
    private final TerritoryMaintenanceRestorationRegistry restorations;

    TerritoryMaintenanceRestorationPaymentCoordinator(
            NationRegistry nations,
            NationFiscalAuthorityRegistry authorities,
            FiscalLedger ledger,
            PaymentCoordinator payments,
            PermanentDestructionCoordinator destructions,
            TerritoryMaintenanceRestorationRegistry restorations) {
        if (nations == null
                || authorities == null
                || ledger == null
                || payments == null
                || destructions == null
                || restorations == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Restoration payment dependencies cannot be null");
        }
        this.nations = nations;
        this.authorities = authorities;
        this.ledger = ledger;
        this.payments = payments;
        this.destructions = destructions;
        this.restorations = restorations;
    }

    public TerritoryMaintenanceRestorationPayment restore(
            PrepareTerritoryMaintenanceRestoration request) {
        Optional<TerritoryMaintenanceRestoration> existing =
                restorations.find(request.serviceIdentity(), request.requestId());
        if (existing.isEmpty()) {
            requireAuthority(request);
        }
        TerritoryMaintenanceRestoration prepared = restorations.prepare(request);
        if (prepared.totalDue().minorUnits() == 0L) {
            TerritoryMaintenanceRestoration committed = restorations.confirm(
                    new ConfirmTerritoryMaintenanceRestoration(
                            request.serviceIdentity(),
                            prepared.restorationId(),
                            null,
                            null,
                            null));
            return new TerritoryMaintenanceRestorationPayment(
                    committed,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    prepared.totalDue(),
                    prepared.totalDue());
        }
        AccountId treasury = new AccountId(
                "nation:" + prepared.nationId().value() + ":treasury");
        var split = TerritoryMaintenancePaymentCoordinator.split(
                prepared.totalDue(), prepared.destructionBasisPoints());
        String purpose = "Territory Maintenance Restoration for "
                + prepared.dimensionId()
                + " "
                + prepared.chunkX()
                + " "
                + prepared.chunkZ()
                + ": "
                + prepared.reason();
        Reservation reservation = ledger.reserve(new ReserveFunds(
                request.serviceIdentity(),
                request.requestId() + ":reserve",
                treasury,
                prepared.totalDue(),
                purpose));
        var publicFundPayment = payments.settle(
                new SettleReservation(
                        request.serviceIdentity(),
                        request.requestId() + ":public-fund",
                        reservation.reservationId(),
                        LightmansCurrencyPublicMaintenanceFundProvisioner.ACCOUNT_ID,
                        split.publicFundAmount()),
                FailurePoint.NONE);
        MonetarySupplyEvent destruction = null;
        if (split.destroyedAmount().minorUnits() > 0L) {
            destruction = destructions.confirm(new ConfirmPermanentDestruction(
                    request.serviceIdentity(),
                    request.requestId() + ":destruction",
                    treasury,
                    split.destroyedAmount(),
                    purpose));
            ledger.release(new ReleaseReservation(
                    request.serviceIdentity(),
                    request.requestId() + ":release",
                    reservation.reservationId(),
                    "Release permanently destroyed Restoration share"));
        }
        TerritoryMaintenanceRestoration committed = restorations.confirm(
                new ConfirmTerritoryMaintenanceRestoration(
                        request.serviceIdentity(),
                        prepared.restorationId(),
                        reservation.reservationId(),
                        publicFundPayment.transactionId(),
                        destruction == null ? null : destructionOperationId(destruction)));
        if (committed.state() != TerritoryMaintenanceRestorationState.CIVIC_COMMITTED) {
            throw new IllegalStateException(
                    "Territory Maintenance Restoration did not commit");
        }
        return new TerritoryMaintenanceRestorationPayment(
                committed,
                Optional.of(reservation),
                Optional.of(publicFundPayment),
                Optional.ofNullable(destruction),
                split.publicFundAmount(),
                split.destroyedAmount());
    }

    private void requireAuthority(PrepareTerritoryMaintenanceRestoration request) {
        var nation = nations.find(request.nationId())
                .orElseThrow(() -> new SecurityException(
                        "Unknown Territory Maintenance Restoration Nation"));
        if (!nation.ftbTeamId().equals(request.ftbTeamId())) {
            throw new SecurityException(
                    "Territory Maintenance Restoration Team is not bound to the exact Nation");
        }
        authorities.require(
                request.nationId(),
                request.actorPlayerId(),
                NationFiscalPermission.MANAGE_TERRITORY_FINANCE);
    }

    private static java.util.UUID destructionOperationId(MonetarySupplyEvent destruction) {
        String prefix = "permanent-destruction:";
        if (!destruction.externalReference().startsWith(prefix)) {
            throw new IllegalStateException(
                    "Territory Maintenance Restoration destruction reference is invalid");
        }
        return java.util.UUID.fromString(
                destruction.externalReference().substring(prefix.length()));
    }
}
