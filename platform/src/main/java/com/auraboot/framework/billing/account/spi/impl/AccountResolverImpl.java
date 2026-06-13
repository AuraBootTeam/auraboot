package com.auraboot.framework.billing.account.spi.impl;

import com.auraboot.framework.billing.account.entity.BillingAccount;
import com.auraboot.framework.billing.account.mapper.BillingAccountMapper;
import com.auraboot.framework.billing.account.spi.AccountResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Default DB-backed implementation of {@link AccountResolver}.
 *
 * <p>Reads {@code ab_tenant.billing_account_id} via {@link JdbcTemplate}
 * (the {@code Tenant} entity does not yet carry this column as a mapped field)
 * and then delegates to {@link BillingAccountMapper#selectById} for the
 * full account row.
 *
 * <p>Per red-line §8: NO self-heal.  If the tenant has no
 * {@code billing_account_id}, or the referenced account row is missing,
 * this method returns {@link Optional#empty()} — it does not create or
 * repair the linkage.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountResolverImpl implements AccountResolver {

    private final JdbcTemplate jdbcTemplate;
    private final BillingAccountMapper billingAccountMapper;

    @Override
    @Transactional(readOnly = true)
    public Optional<BillingAccount> resolveByTenant(Long tenantId) {
        // Step 1: read billing_account_id from ab_tenant
        List<Long> accountIds = jdbcTemplate.query(
                "SELECT billing_account_id FROM ab_tenant WHERE id = ? AND deleted_flag = FALSE",
                (rs, rowNum) -> rs.getObject(1, Long.class),
                tenantId
        );

        if (accountIds.isEmpty() || accountIds.get(0) == null) {
            log.debug("resolveByTenant: tenant {} has no billing_account_id", tenantId);
            return Optional.empty();
        }

        Long accountId = accountIds.get(0);

        // Step 2: load the BillingAccount row
        BillingAccount account = billingAccountMapper.selectById(accountId);
        if (account == null) {
            log.warn("resolveByTenant: tenant {} points to missing billing account id={}", tenantId, accountId);
            return Optional.empty();
        }

        return Optional.of(account);
    }
}
