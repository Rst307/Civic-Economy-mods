package org.civiceconomy.territory;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.FailurePoint;
import org.civiceconomy.fiscal.FiscalLedger;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.fiscal.PaymentCoordinator;
import org.civiceconomy.fiscal.Reservation;
import org.civiceconomy.fiscal.ReserveFunds;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.fiscal.SettleReservation;
import org.civiceconomy.fiscal.TransactionState;
import org.civiceconomy.nation.NationFiscalAuthorityRegistry;
import org.civiceconomy.nation.NationFiscalPermission;
import org.civiceconomy.nation.NationRegistry;

public final class TerritoryClaimPrepaymentCoordinator {
    private final NationRegistry nations;
    private final NationFiscalAuthorityRegistry authorities;
    private final TerritoryClearingAccountProvisioner clearingAccounts;
    private final FiscalLedger ledger;
    private final PaymentCoordinator payments;
    private final TerritoryClaimPermitRegistry permits;
    private final ServiceIdentity serviceIdentity;
    private final AccountId clearingAccount;
    private final Clock clock;
    private final ConcurrentHashMap<Target, Object> targetLocks = new ConcurrentHashMap<>();

    public TerritoryClaimPrepaymentCoordinator(
            NationRegistry nations,
            NationFiscalAuthorityRegistry authorities,
            TerritoryClearingAccountProvisioner clearingAccounts,
            FiscalLedger ledger,
            PaymentCoordinator payments,
            TerritoryClaimPermitRegistry permits,
            ServiceIdentity serviceIdentity,
            AccountId clearingAccount,
            Clock clock) {
        if (nations == null
                || authorities == null
                || clearingAccounts == null
                || ledger == null
                || payments == null
                || permits == null
                || serviceIdentity == null
                || clearingAccount == null
                || clock == null) {
            throw new IllegalArgumentException("Territory Claim prepayment dependencies cannot be null");
        }
        this.nations = nations;
        this.authorities = authorities;
        this.clearingAccounts = clearingAccounts;
        this.ledger = ledger;
        this.payments = payments;
        this.permits = permits;
        this.serviceIdentity = serviceIdentity;
        this.clearingAccount = clearingAccount;
        this.clock = clock;
    }

    public TerritoryClaimPermit prepare(PrepareTerritoryClaimPrepayment request) {
        Target target = new Target(
                request.nationId().value(), request.dimensionId(), request.chunkX(), request.chunkZ());
        synchronized (targetLocks.computeIfAbsent(target, ignored -> new Object())) {
            try {
                var replay = permits.findByRequest(
                        serviceIdentity, request.requestId() + ":permit");
                if (replay.isPresent()) {
                    requireReplayPayload(replay.orElseThrow(), request);
                    return replay.orElseThrow();
                }
                requireAuthority(request);
                if (permits.findByTarget(
                                request.nationId(),
                                request.dimensionId(),
                                request.chunkX(),
                                request.chunkZ())
                        .filter(permit -> permit.state() == TerritoryClaimPermitState.READY)
                        .isPresent()) {
                    throw new IllegalStateException("A READY Territory Claim Permit already exists for the target");
                }
                if (!request.expiresAt().isAfter(clock.instant())) {
                    throw new IllegalArgumentException("Territory Claim Permit expiry must be in the future");
                }
                clearingAccounts.ensureExists(clearingAccount);
                AccountId treasury = new AccountId(
                        "nation:" + request.nationId().value() + ":treasury");
                Reservation reservation = ledger.reserve(new ReserveFunds(
                        serviceIdentity,
                        request.requestId() + ":reserve",
                        treasury,
                        request.quote().prepayment(),
                        "Territory Claim prepayment for " + request.dimensionId()
                                + " " + request.chunkX() + " " + request.chunkZ()));
                var payment = payments.settle(
                        new SettleReservation(
                                serviceIdentity,
                                request.requestId() + ":payment",
                                reservation.reservationId(),
                                clearingAccount,
                                request.quote().prepayment()),
                        FailurePoint.NONE);
                if (payment.state() != TransactionState.CIVIC_COMMITTED) {
                    throw new IllegalStateException(
                            "Territory Claim prepayment did not commit " + payment.transactionId());
                }
                return permits.issue(new IssueTerritoryClaimPermit(
                        serviceIdentity,
                        request.requestId() + ":permit",
                        request.nationId(),
                        request.ftbTeamId(),
                        request.actorPlayerId(),
                        request.dimensionId(),
                        request.chunkX(),
                        request.chunkZ(),
                        request.quote().currentClaimedChunks(),
                        request.quotedFreeAllocation(),
                        request.quote().prepayment().minorUnits(),
                        payment.transactionId(),
                        request.expiresAt()));
            } finally {
                targetLocks.remove(target);
            }
        }
    }

    private void requireAuthority(PrepareTerritoryClaimPrepayment request) {
        var nation = nations.find(request.nationId())
                .orElseThrow(() -> new SecurityException("Unknown Territory Claim Nation"));
        if (!nation.ftbTeamId().equals(request.ftbTeamId())) {
            throw new SecurityException("Territory Claim FTB Team is not bound to the exact Nation");
        }
        authorities.require(
                request.nationId(),
                request.actorPlayerId(),
                NationFiscalPermission.MANAGE_TERRITORY_FINANCE);
    }

    private void requireReplayPayload(
            TerritoryClaimPermit permit, PrepareTerritoryClaimPrepayment request) {
        if (!permit.nationId().equals(request.nationId())
                || !permit.ftbTeamId().equals(request.ftbTeamId())
                || !permit.actorPlayerId().equals(request.actorPlayerId())
                || !permit.dimensionId().equals(request.dimensionId())
                || permit.chunkX() != request.chunkX()
                || permit.chunkZ() != request.chunkZ()
                || permit.quotedCurrentClaimedChunks()
                        != request.quote().currentClaimedChunks()
                || permit.quotedFreeAllocation() != request.quotedFreeAllocation()
                || !permit.prepayment().equals(request.quote().prepayment())
                || !permit.expiresAt().equals(request.expiresAt())) {
            throw new IdempotencyConflictException(serviceIdentity, request.requestId());
        }
    }

    private record Target(
            java.util.UUID nationId, String dimensionId, int chunkX, int chunkZ) {}
}
