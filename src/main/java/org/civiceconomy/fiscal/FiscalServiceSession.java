package org.civiceconomy.fiscal;

import net.neoforged.fml.ModContainer;

public final class FiscalServiceSession {
    private final ServiceIdentity serviceIdentity;
    private final String ownerModId;
    private final ModContainer ownerContainer;

    FiscalServiceSession(ServiceIdentity serviceIdentity, String ownerModId) {
        this.serviceIdentity = serviceIdentity;
        this.ownerModId = ownerModId;
        this.ownerContainer = null;
    }

    FiscalServiceSession(ServiceIdentity serviceIdentity, ModContainer ownerContainer) {
        this.serviceIdentity = serviceIdentity;
        this.ownerModId = ownerContainer.getModId();
        this.ownerContainer = ownerContainer;
    }

    void requireIdentity(ServiceIdentity requestedIdentity) {
        if (!serviceIdentity.equals(requestedIdentity)) {
            throw new FiscalServiceIdentityMismatchException(serviceIdentity, requestedIdentity);
        }
    }

    ServiceIdentity serviceIdentity() {
        return serviceIdentity;
    }

    String ownerModId() {
        return ownerModId;
    }

    boolean isBoundToLoadedModContainer() {
        return ownerContainer != null;
    }
}
