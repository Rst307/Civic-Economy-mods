package org.civiceconomy.fiscal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.neoforged.fml.ModContainer;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredFiscalService;
import org.civiceconomy.platform.neoforge.NeoForgeCallerModResolver;

public final class FiscalAuthorization {
    private final CivicDatabase database;

    public FiscalAuthorization(CivicDatabase database) {
        this.database = database;
    }

    public RegisteredFiscalService register(RegisterFiscalService request) {
        StoredFiscalService replay = database.fiscalServiceRegistration(
                request.administrator().value(), request.requestId());
        if (replay != null) {
            if (!replay.serviceIdentity().equals(request.serviceIdentity().value())
                    || !replay.ownerModId().equals(request.ownerModId())
                    || !replay.displayName().equals(request.displayName())
                    || !replay.reason().equals(request.reason())) {
                throw new FiscalServiceRegistrationConflictException(request.serviceIdentity());
            }
            return toRegisteredService(replay);
        }
        StoredFiscalService stored = database.fiscalService(request.serviceIdentity().value());
        if (stored == null) {
            stored = database.registerFiscalService(
                    request.serviceIdentity().value(),
                    request.ownerModId(),
                    request.displayName(),
                    request.administrator().value(),
                    request.requestId(),
                    request.reason(),
                    System.currentTimeMillis());
        }
        if (!stored.ownerModId().equals(request.ownerModId())
                || !stored.displayName().equals(request.displayName())) {
            throw new FiscalServiceRegistrationConflictException(request.serviceIdentity());
        }
        return toRegisteredService(stored);
    }

    FiscalServiceSession openSession(
            ServiceIdentity serviceIdentity, String verifiedOwnerModId) {
        StoredFiscalService stored = database.fiscalService(serviceIdentity.value());
        if (stored == null) {
            throw new UnknownFiscalServiceException(serviceIdentity);
        }
        if (!stored.ownerModId().equals(verifiedOwnerModId)) {
            throw new FiscalServiceOwnerMismatchException(
                    serviceIdentity, stored.ownerModId(), verifiedOwnerModId);
        }
        return new FiscalServiceSession(serviceIdentity, stored.ownerModId());
    }

    public FiscalServiceSession openSession(ServiceIdentity serviceIdentity) {
        ModContainer verifiedOwner = NeoForgeCallerModResolver.resolveCallingModContainer();
        StoredFiscalService stored = database.fiscalService(serviceIdentity.value());
        if (stored == null) {
            throw new UnknownFiscalServiceException(serviceIdentity);
        }
        if (!stored.ownerModId().equals(verifiedOwner.getModId())) {
            throw new FiscalServiceOwnerMismatchException(
                    serviceIdentity, stored.ownerModId(), verifiedOwner.getModId());
        }
        return new FiscalServiceSession(serviceIdentity, verifiedOwner);
    }

    public FiscalCapabilityGrant grant(GrantFiscalCapability request) {
        if (database.fiscalService(request.serviceIdentity().value()) == null) {
            throw new UnknownFiscalServiceException(request.serviceIdentity());
        }
        var replay = database.fiscalCapabilityGrant(
                request.administrator().value(), request.requestId());
        if (replay != null) {
            if (!replay.serviceIdentity().equals(request.serviceIdentity().value())
                    || !replay.capability().equals(request.capability().name())
                    || !replay.accountId().equals(request.accountId().value())
                    || !replay.reason().equals(request.reason())) {
                throw new FiscalGrantConflictException(
                        request.administrator(), request.requestId());
            }
            return toCapabilityGrant(replay);
        }
        var existing = database.fiscalCapabilityGrant(
                request.serviceIdentity().value(),
                request.capability().name(),
                request.accountId().value());
        if (existing != null) {
            return toCapabilityGrant(existing);
        }
        var stored = database.grantFiscalCapability(
                java.util.UUID.randomUUID(),
                request.administrator().value(),
                request.requestId(),
                request.serviceIdentity().value(),
                request.capability().name(),
                request.accountId().value(),
                request.reason(),
                System.currentTimeMillis());
        if (stored.administratorIdentity().equals(request.administrator().value())
                && stored.requestId().equals(request.requestId())
                && (!stored.serviceIdentity().equals(request.serviceIdentity().value())
                        || !stored.capability().equals(request.capability().name())
                        || !stored.accountId().equals(request.accountId().value())
                        || !stored.reason().equals(request.reason()))) {
            throw new FiscalGrantConflictException(request.administrator(), request.requestId());
        }
        return toCapabilityGrant(stored);
    }

    public FiscalCapabilityRevocation revoke(RevokeFiscalCapability request) {
        var replay = database.fiscalCapabilityRevocation(
                request.administrator().value(), request.requestId());
        if (replay != null) {
            if (!replay.grantId().equals(request.grantId())
                    || !replay.reason().equals(request.reason())) {
                throw new FiscalRevocationConflictException(
                        request.administrator(), request.requestId());
            }
            return toCapabilityRevocation(replay);
        }
        var grant = database.fiscalCapabilityGrant(request.grantId());
        if (grant == null) {
            throw new UnknownFiscalGrantException(request.grantId());
        }
        var existing = database.fiscalCapabilityRevocation(request.grantId());
        if (existing != null) {
            return toCapabilityRevocation(existing);
        }
        var stored = database.revokeFiscalCapability(
                java.util.UUID.randomUUID(),
                request.grantId(),
                request.administrator().value(),
                request.requestId(),
                request.reason(),
                System.currentTimeMillis());
        if (stored.administratorIdentity().equals(request.administrator().value())
                && stored.requestId().equals(request.requestId())
                && (!stored.grantId().equals(request.grantId())
                        || !stored.reason().equals(request.reason()))) {
            throw new FiscalRevocationConflictException(
                    request.administrator(), request.requestId());
        }
        return toCapabilityRevocation(stored);
    }

    public FiscalServiceStateChange changeState(ChangeFiscalServiceState request) {
        if (database.fiscalService(request.serviceIdentity().value()) == null) {
            throw new UnknownFiscalServiceException(request.serviceIdentity());
        }
        var replay = database.fiscalServiceStateChange(
                request.administrator().value(), request.requestId());
        if (replay != null) {
            if (!replay.serviceIdentity().equals(request.serviceIdentity().value())
                    || !replay.state().equals(request.state().name())
                    || !replay.reason().equals(request.reason())) {
                throw new FiscalServiceStateConflictException(
                        request.administrator(), request.requestId());
            }
            return toServiceStateChange(replay);
        }
        return toServiceStateChange(database.changeFiscalServiceState(
                java.util.UUID.randomUUID(),
                request.administrator().value(),
                request.requestId(),
                request.serviceIdentity().value(),
                request.state().name(),
                request.reason(),
                System.currentTimeMillis()));
    }

    public List<RegisteredFiscalService> registeredServices() {
        return database.fiscalServices().stream()
                .map(FiscalAuthorization::toRegisteredService)
                .toList();
    }

    public FiscalServiceAuthorizationView describe(ServiceIdentity serviceIdentity) {
        StoredFiscalService stored = database.fiscalService(serviceIdentity.value());
        if (stored == null) {
            throw new UnknownFiscalServiceException(serviceIdentity);
        }
        List<FiscalCapabilityGrantStatus> grants = database
                .fiscalCapabilityGrants(serviceIdentity.value())
                .stream()
                .map(grant -> {
                    var revocation = database.fiscalCapabilityRevocation(grant.grantId());
                    return new FiscalCapabilityGrantStatus(
                            toCapabilityGrant(grant),
                            Optional.ofNullable(revocation).map(this::toCapabilityRevocation));
                })
                .toList();
        return new FiscalServiceAuthorizationView(
                toRegisteredService(stored),
                FiscalServiceState.valueOf(database.fiscalServiceState(serviceIdentity.value())),
                grants);
    }

    void require(
            ServiceIdentity serviceIdentity, FiscalCapability capability, AccountId accountId) {
        if (database.fiscalService(serviceIdentity.value()) == null
                || !database.isFiscalServiceEnabled(serviceIdentity.value())
                || !database.hasFiscalCapability(
                        serviceIdentity.value(), capability.name(), accountId.value())) {
            throw new FiscalAccessDeniedException(serviceIdentity, capability, accountId);
        }
    }

    private static RegisteredFiscalService toRegisteredService(StoredFiscalService stored) {
        return new RegisteredFiscalService(
                new ServiceIdentity(stored.serviceIdentity()),
                stored.ownerModId(),
                stored.displayName(),
                new ServiceIdentity(stored.administratorIdentity()),
                stored.requestId(),
                stored.reason(),
                Instant.ofEpochMilli(stored.registeredAtEpochMillis()));
    }

    private static FiscalCapabilityGrant toCapabilityGrant(
            org.civiceconomy.persistence.StoredFiscalCapabilityGrant stored) {
        return new FiscalCapabilityGrant(
                stored.grantId(),
                new ServiceIdentity(stored.administratorIdentity()),
                stored.requestId(),
                new ServiceIdentity(stored.serviceIdentity()),
                FiscalCapability.valueOf(stored.capability()),
                new AccountId(stored.accountId()),
                stored.reason(),
                Instant.ofEpochMilli(stored.grantedAtEpochMillis()));
    }

    private FiscalCapabilityRevocation toCapabilityRevocation(
            org.civiceconomy.persistence.StoredFiscalCapabilityRevocation stored) {
        var grant = database.fiscalCapabilityGrant(stored.grantId());
        if (grant == null) {
            throw new IllegalStateException(
                    "Fiscal revocation references a missing grant " + stored.grantId());
        }
        return new FiscalCapabilityRevocation(
                stored.revocationId(),
                stored.grantId(),
                toCapabilityGrant(grant),
                new ServiceIdentity(stored.administratorIdentity()),
                stored.requestId(),
                stored.reason(),
                Instant.ofEpochMilli(stored.revokedAtEpochMillis()));
    }

    private static FiscalServiceStateChange toServiceStateChange(
            org.civiceconomy.persistence.StoredFiscalServiceStateChange stored) {
        return new FiscalServiceStateChange(
                stored.changeId(),
                new ServiceIdentity(stored.administratorIdentity()),
                stored.requestId(),
                new ServiceIdentity(stored.serviceIdentity()),
                FiscalServiceState.valueOf(stored.state()),
                stored.reason(),
                Instant.ofEpochMilli(stored.changedAtEpochMillis()));
    }
}
