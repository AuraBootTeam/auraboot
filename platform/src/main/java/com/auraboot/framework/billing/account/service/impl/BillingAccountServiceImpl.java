package com.auraboot.framework.billing.account.service.impl;

import com.auraboot.framework.billing.account.entity.BillingAccount;
import com.auraboot.framework.billing.account.mapper.BillingAccountMapper;
import com.auraboot.framework.billing.account.service.BillingAccountService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Default DB-backed implementation of {@link BillingAccountService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingAccountServiceImpl implements BillingAccountService {

    private final BillingAccountMapper billingAccountMapper;

    @Override
    @Transactional
    public Long createAccount(String accountCode, String name) {
        BillingAccount account = BillingAccount.builder()
                .pid(UniqueIdGenerator.generate())
                .accountCode(accountCode)
                .name(name)
                .status("active")
                .defaultCurrency("CNY")
                .billingMode("POSTPAID")
                .balance(BigDecimal.ZERO)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .deletedFlag(false)
                .build();

        billingAccountMapper.insert(account);

        log.debug("Created BillingAccount id={} accountCode={}", account.getId(), accountCode);
        return account.getId();
    }
}
