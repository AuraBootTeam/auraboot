package com.auraboot.module.finance;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.currency.dao.entity.ExchangeRate;
import com.auraboot.framework.currency.dao.mapper.ExchangeRateMapper;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.module.finance.dto.ConsolidationReportRequest;
import com.auraboot.module.finance.dto.ConsolidationReportResult;
import com.auraboot.module.finance.dto.LegalEntityCreateRequest;
import com.auraboot.module.finance.entity.IntercompanyTxn;
import com.auraboot.module.finance.entity.LegalEntity;
import com.auraboot.module.finance.mapper.IntercompanyTxnMapper;
import com.auraboot.module.finance.service.ConsolidationReportService;
import com.auraboot.module.finance.service.LegalEntityService;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ConsolidationReportService}.
 *
 * <p>Test plan:
 * <ul>
 *   <li>CR-01: consolidate entity financials and eliminate intercompany transactions</li>
 *   <li>CR-02: with no entity financials, still eliminate pending intercompany transactions</li>
 * </ul>
 */
@Slf4j
class ConsolidationReportServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ConsolidationReportService consolidationReportService;

    @Autowired
    private LegalEntityService legalEntityService;

    @Autowired
    private IntercompanyTxnMapper intercompanyTxnMapper;

    @Autowired
    private ExchangeRateMapper exchangeRateMapper;

    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 3, 19);
    private final String runId = String.valueOf(System.currentTimeMillis());

    @BeforeEach
    void seedExchangeRates() {
        // Seed USD -> CNY rate so currency conversion works in tests
        insertRate("usd", "cny", new BigDecimal("7.24500000"), REPORT_DATE);
        // Seed CNY -> USD (reverse) as well for completeness
        insertRate("cny", "usd", new BigDecimal("0.13802000"), REPORT_DATE);
    }

    // ==================== CR-01 ====================

    @Test
    @DisplayName("CR-01: consolidate entity financials and eliminate intercompany transactions")
    void generate_shouldConsolidateEntityFinancials_andEliminateIntercompanyTxns() {
        Long tenantId = MetaContext.getCurrentTenantId();

        // Create HQ (CNY, parent)
        LegalEntity hq = legalEntityService.create(buildEntityReq("HQ-" + runId, "HQ " + runId, null, "cny", true));
        // Create SH_SUB (USD, child of HQ)
        LegalEntity shSub = legalEntityService.create(buildEntityReq("SH-" + runId, "Shanghai Sub " + runId,
                hq.getId(), "usd", false));

        // Insert one pending intercompany transaction: SH_SUB SALE HQ, 1000 USD
        IntercompanyTxn txn = buildTxn(tenantId, shSub.getId(), hq.getId(), "sale",
                new BigDecimal("1000.00"), "usd");
        intercompanyTxnMapper.insert(txn);

        // Build request with financials for both entities
        ConsolidationReportRequest request = new ConsolidationReportRequest();
        request.setReportingDate(REPORT_DATE);
        request.setReportingCurrency("cny");

        ConsolidationReportRequest.EntityFinancial hqFinancial = new ConsolidationReportRequest.EntityFinancial();
        hqFinancial.setEntityId(hq.getId());
        hqFinancial.setRevenue(new BigDecimal("5000.00"));
        hqFinancial.setExpenses(new BigDecimal("3000.00"));
        hqFinancial.setAssets(new BigDecimal("50000.00"));
        hqFinancial.setLiabilities(new BigDecimal("20000.00"));

        ConsolidationReportRequest.EntityFinancial subFinancial = new ConsolidationReportRequest.EntityFinancial();
        subFinancial.setEntityId(shSub.getId());
        subFinancial.setRevenue(new BigDecimal("2000.00"));
        subFinancial.setExpenses(new BigDecimal("1500.00"));
        subFinancial.setAssets(new BigDecimal("30000.00"));
        subFinancial.setLiabilities(new BigDecimal("10000.00"));

        request.setEntityFinancials(List.of(hqFinancial, subFinancial));

        // Execute
        ConsolidationReportResult result = consolidationReportService.generate(request, tenantId);

        // Assertions
        assertThat(result).isNotNull();
        assertThat(result.getEliminatedTransactions()).hasSize(1);
        assertThat(result.getTotalEliminatedAmount()).isGreaterThan(BigDecimal.ZERO);
        assertThat(result.getConsolidatedRevenue()).isGreaterThan(BigDecimal.ZERO);
        assertThat(result.getEntityCount()).isGreaterThanOrEqualTo(2);
        assertThat(result.getReportingCurrency()).isEqualTo("cny");
        assertThat(result.getReportingDate()).isEqualTo(REPORT_DATE);
        assertThat(result.getConsolidatedNetIncome())
                .isEqualByComparingTo(result.getConsolidatedRevenue().subtract(result.getConsolidatedExpenses()));
        assertThat(result.getConsolidatedEquity())
                .isEqualByComparingTo(result.getConsolidatedAssets().subtract(result.getConsolidatedLiabilities()));

        log.info("CR-01 passed: entityCount={} eliminatedTxns={} totalEliminated={} revenue={}",
                result.getEntityCount(), result.getEliminatedTransactions().size(),
                result.getTotalEliminatedAmount(), result.getConsolidatedRevenue());
    }

    // ==================== CR-02 ====================

    @Test
    @DisplayName("CR-02: with no entity financials, still eliminate pending intercompany transactions")
    void generate_withNoEntityFinancials_shouldStillEliminateTransactions() {
        Long tenantId = MetaContext.getCurrentTenantId();

        // Create two entities
        LegalEntity entityA = legalEntityService.create(buildEntityReq("A2-" + runId, "Entity A2 " + runId,
                null, "cny", true));
        LegalEntity entityB = legalEntityService.create(buildEntityReq("B2-" + runId, "Entity B2 " + runId,
                entityA.getId(), "usd", false));

        // Insert one pending intercompany transaction
        IntercompanyTxn txn = buildTxn(tenantId, entityB.getId(), entityA.getId(), "service",
                new BigDecimal("500.00"), "usd");
        intercompanyTxnMapper.insert(txn);

        // Build request with empty entity financials
        ConsolidationReportRequest request = new ConsolidationReportRequest();
        request.setReportingDate(REPORT_DATE);
        request.setReportingCurrency("cny");
        request.setEntityFinancials(List.of());  // empty list

        // Execute
        ConsolidationReportResult result = consolidationReportService.generate(request, tenantId);

        // Assertions: eliminations still happen even without entity financials
        assertThat(result).isNotNull();
        assertThat(result.getEliminatedTransactions()).isNotEmpty();
        assertThat(result.getTotalEliminatedAmount()).isGreaterThan(BigDecimal.ZERO);
        assertThat(result.getEntityCount()).isGreaterThanOrEqualTo(2);

        log.info("CR-02 passed: eliminatedTxns={} totalEliminated={}",
                result.getEliminatedTransactions().size(), result.getTotalEliminatedAmount());
    }

    // ==================== Helpers ====================

    private LegalEntityCreateRequest buildEntityReq(String code, String name, Long parentId,
                                                     String currency, boolean isParent) {
        LegalEntityCreateRequest req = new LegalEntityCreateRequest();
        req.setEntityCode(code);
        req.setEntityName(name);
        req.setParentId(parentId);
        req.setCurrency(currency);
        req.setIsParent(isParent);
        return req;
    }

    private IntercompanyTxn buildTxn(Long tenantId, Long fromEntityId, Long toEntityId,
                                      String txnType, BigDecimal amount, String currency) {
        IntercompanyTxn txn = new IntercompanyTxn();
        txn.setId(IdWorker.getId());
        txn.setPid(UniqueIdGenerator.generate());
        txn.setTenantId(tenantId);
        txn.setFromEntityId(fromEntityId);
        txn.setToEntityId(toEntityId);
        txn.setTxnDate(REPORT_DATE);
        txn.setTxnType(txnType);
        txn.setAmount(amount);
        txn.setCurrency(currency);
        txn.setDescription("Test txn " + runId);
        txn.setIsEliminated(false);
        txn.setCreatedAt(Instant.now());
        return txn;
    }

    private void insertRate(String base, String target, BigDecimal rate, LocalDate date) {
        ExchangeRate entity = new ExchangeRate();
        entity.setPid(UniqueIdGenerator.generate());
        entity.setTenantId(testTenant.getId());
        entity.setBaseCurrency(base);
        entity.setTargetCurrency(target);
        entity.setRate(rate);
        entity.setEffectiveDate(date);
        entity.setSource("manual");
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        entity.setDeletedFlag(false);
        entity.setCreatedBy(testUser.getId());
        exchangeRateMapper.insert(entity);
    }
}
