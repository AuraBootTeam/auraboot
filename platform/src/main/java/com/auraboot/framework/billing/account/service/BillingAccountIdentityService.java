package com.auraboot.framework.billing.account.service;

/**
 * Service for managing {@link com.auraboot.framework.billing.account.entity.BillingAccount} lifecycle.
 */
public interface BillingAccountIdentityService {

    /**
     * Create a new billing account with the given code and name.
     *
     * <p>The account is created with {@code status = active},
     * {@code defaultCurrency = CNY}, {@code billingMode = POSTPAID},
     * and {@code balance = 0}.
     *
     * @param accountCode unique machine-readable code for this account
     * @param name        human-readable display name
     * @return the auto-generated database {@code id} of the created account
     */
    Long createAccount(String accountCode, String name);
}
