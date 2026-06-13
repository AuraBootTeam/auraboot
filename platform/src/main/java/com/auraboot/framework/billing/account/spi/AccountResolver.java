package com.auraboot.framework.billing.account.spi;

import com.auraboot.framework.billing.account.entity.BillingAccount;

import java.util.Optional;

/**
 * SPI: resolves a tenant to its linked {@link BillingAccount}.
 *
 * <p>Implementations must NOT self-heal: if the tenant has no
 * {@code billing_account_id} or the referenced account is missing,
 * return {@link Optional#empty()}.  Creating or repairing the linkage
 * is the responsibility of the provisioning layer, not this resolver.
 */
public interface AccountResolver {

    /**
     * Resolve the billing account for the given tenant.
     *
     * @param tenantId the tenant's database id
     * @return the linked {@link BillingAccount}, or {@link Optional#empty()}
     *         if the tenant has no {@code billing_account_id} or if no
     *         account row is found for the stored id
     */
    Optional<BillingAccount> resolveByTenant(Long tenantId);
}
