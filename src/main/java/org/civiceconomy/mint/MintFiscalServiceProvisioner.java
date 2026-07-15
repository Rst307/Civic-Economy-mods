package org.civiceconomy.mint;

import org.civiceconomy.fiscal.FiscalAuthorization;
import org.civiceconomy.fiscal.FiscalCapability;
import org.civiceconomy.fiscal.GrantFiscalCapability;
import org.civiceconomy.fiscal.RegisterFiscalService;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.issuance.IssuanceQuotaRegistry;
import org.civiceconomy.nation.NationId;

public final class MintFiscalServiceProvisioner {
    public static final ServiceIdentity SERVICE_IDENTITY =
            new ServiceIdentity("civiceconomy-mint");
    private static final ServiceIdentity INTERNAL_ADMINISTRATOR =
            new ServiceIdentity("civiceconomy-internal");

    private final FiscalAuthorization authorization;

    public MintFiscalServiceProvisioner(FiscalAuthorization authorization) {
        if (authorization == null) {
            throw new IllegalArgumentException("Fiscal Authorization cannot be null");
        }
        this.authorization = authorization;
    }

    public void ensureAuthorized(NationId nationId) {
        if (nationId == null) {
            throw new IllegalArgumentException("Mint fiscal Nation cannot be null");
        }
        authorization.register(new RegisterFiscalService(
                SERVICE_IDENTITY,
                "civiceconomy",
                "Civic Mint Batch Coordinator",
                INTERNAL_ADMINISTRATOR,
                "mint-service-registration-v1",
                "Internal service for authorized Mint Batch custody"));
        authorization.grant(new GrantFiscalCapability(
                INTERNAL_ADMINISTRATOR,
                "mint-service-grant-v1:MANAGE_ISSUANCE:" + nationId.value(),
                SERVICE_IDENTITY,
                FiscalCapability.MANAGE_ISSUANCE,
                IssuanceQuotaRegistry.treasury(nationId),
                "Exact National Treasury scope for Mint Batch custody"));
    }
}
