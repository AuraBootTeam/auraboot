package com.auraboot.module.finance.service;

import com.auraboot.framework.currency.service.CurrencyService;
import com.auraboot.module.finance.dto.ConsolidationReportRequest;
import com.auraboot.module.finance.dto.ConsolidationReportResult;
import com.auraboot.module.finance.entity.IntercompanyTxn;
import com.auraboot.module.finance.entity.LegalEntity;
import com.auraboot.module.finance.mapper.IntercompanyTxnMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link ConsolidationReportService}.
 *
 * <p>Consolidation is performed atomically within a single transaction so that
 * intercompany eliminations are either all committed or all rolled back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsolidationReportServiceImpl implements ConsolidationReportService {

    private static final int SCALE = 6;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final CurrencyService currencyService;
    private final LegalEntityService legalEntityService;
    private final IntercompanyTxnMapper intercompanyTxnMapper;

    @Override
    @Transactional
    public ConsolidationReportResult generate(ConsolidationReportRequest request, Long tenantId) {
        log.info("Starting consolidation report for tenantId={} reportingDate={}", tenantId, request.getReportingDate());

        // Step 1: Load all legal entities and build id→entity map
        List<LegalEntity> entities = legalEntityService.findAll(tenantId);
        Map<Long, LegalEntity> entityMap = entities.stream()
                .collect(Collectors.toMap(LegalEntity::getId, Function.identity()));

        // Step 2: Determine reporting currency
        String reportingCurrency = resolveReportingCurrency(request, entities);
        log.info("Reporting currency resolved to: {}", reportingCurrency);

        // Step 3 & 4: Convert and sum entity financials
        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalExpenses = BigDecimal.ZERO;
        BigDecimal totalAssets = BigDecimal.ZERO;
        BigDecimal totalLiabilities = BigDecimal.ZERO;

        List<ConsolidationReportRequest.EntityFinancial> financials = request.getEntityFinancials();
        if (financials != null && !financials.isEmpty()) {
            for (ConsolidationReportRequest.EntityFinancial ef : financials) {
                LegalEntity entity = entityMap.get(ef.getEntityId());
                String entityCurrency = (entity != null) ? entity.getCurrency() : reportingCurrency;

                BigDecimal revenue = convertSafe(ef.getRevenue(), entityCurrency, reportingCurrency, request, tenantId);
                BigDecimal expenses = convertSafe(ef.getExpenses(), entityCurrency, reportingCurrency, request, tenantId);
                BigDecimal assets = convertSafe(ef.getAssets(), entityCurrency, reportingCurrency, request, tenantId);
                BigDecimal liabilities = convertSafe(ef.getLiabilities(), entityCurrency, reportingCurrency, request, tenantId);

                totalRevenue = totalRevenue.add(revenue);
                totalExpenses = totalExpenses.add(expenses);
                totalAssets = totalAssets.add(assets);
                totalLiabilities = totalLiabilities.add(liabilities);

                log.info("Entity id={} currency={} revenue={} -> {}{}", ef.getEntityId(), entityCurrency,
                        ef.getRevenue(), revenue, reportingCurrency);
            }
        } else {
            log.info("No entityFinancials provided, skipping financial aggregation");
        }

        // Step 5: Eliminate pending intercompany transactions
        List<IntercompanyTxn> pendingTxns = intercompanyTxnMapper.findPendingEliminations(tenantId);
        log.info("Found {} pending intercompany transactions to eliminate", pendingTxns.size());

        List<IntercompanyTxn> eliminatedTxns = new ArrayList<>();
        BigDecimal totalEliminatedAmount = BigDecimal.ZERO;

        for (IntercompanyTxn txn : pendingTxns) {
            BigDecimal convertedAmount = convertSafe(txn.getAmount(), txn.getCurrency(), reportingCurrency, request, tenantId);

            switch (txn.getTxnType()) {
                case "sale":
                case "dividend":
                case "service":
                    // Eliminate from both revenue and expenses
                    totalRevenue = totalRevenue.subtract(convertedAmount);
                    totalExpenses = totalExpenses.subtract(convertedAmount);
                    break;
                case "loan":
                    // Eliminate from assets and liabilities
                    totalAssets = totalAssets.subtract(convertedAmount);
                    totalLiabilities = totalLiabilities.subtract(convertedAmount);
                    break;
                default:
                    log.warn("Unknown txnType '{}' for txn id={}, treating as revenue/expense elimination",
                            txn.getTxnType(), txn.getId());
                    totalRevenue = totalRevenue.subtract(convertedAmount);
                    totalExpenses = totalExpenses.subtract(convertedAmount);
                    break;
            }

            totalEliminatedAmount = totalEliminatedAmount.add(convertedAmount);

            // Mark as eliminated in DB
            txn.setIsEliminated(true);
            intercompanyTxnMapper.updateById(txn);
            eliminatedTxns.add(txn);

            log.info("Eliminated txn id={} type={} amount={}{} -> {}{}",
                    txn.getId(), txn.getTxnType(), txn.getAmount(), txn.getCurrency(),
                    convertedAmount, reportingCurrency);
        }

        // Step 6: Compute derived totals
        BigDecimal consolidatedNetIncome = totalRevenue.subtract(totalExpenses)
                .setScale(SCALE, ROUNDING);
        BigDecimal consolidatedEquity = totalAssets.subtract(totalLiabilities)
                .setScale(SCALE, ROUNDING);

        ConsolidationReportResult result = ConsolidationReportResult.builder()
                .reportingDate(request.getReportingDate())
                .reportingCurrency(reportingCurrency)
                .consolidatedRevenue(totalRevenue.setScale(SCALE, ROUNDING))
                .consolidatedExpenses(totalExpenses.setScale(SCALE, ROUNDING))
                .consolidatedNetIncome(consolidatedNetIncome)
                .consolidatedAssets(totalAssets.setScale(SCALE, ROUNDING))
                .consolidatedLiabilities(totalLiabilities.setScale(SCALE, ROUNDING))
                .consolidatedEquity(consolidatedEquity)
                .eliminatedTransactions(eliminatedTxns)
                .totalEliminatedAmount(totalEliminatedAmount.setScale(SCALE, ROUNDING))
                .entityCount(entities.size())
                .build();

        log.info("Consolidation complete: entityCount={} eliminatedTxns={} totalEliminated={} revenue={} netIncome={}",
                entities.size(), eliminatedTxns.size(), totalEliminatedAmount,
                result.getConsolidatedRevenue(), result.getConsolidatedNetIncome());

        return result;
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Resolve the reporting currency from the request or fall back to the parent entity's currency.
     */
    private String resolveReportingCurrency(ConsolidationReportRequest request, List<LegalEntity> entities) {
        if (request.getReportingCurrency() != null && !request.getReportingCurrency().isBlank()) {
            return request.getReportingCurrency();
        }
        // Fall back to parent entity currency
        return entities.stream()
                .filter(e -> Boolean.TRUE.equals(e.getIsParent()))
                .map(LegalEntity::getCurrency)
                .findFirst()
                .orElseGet(() -> entities.isEmpty() ? "cny" : entities.get(0).getCurrency());
    }

    /**
     * Convert {@code amount} from {@code fromCurrency} to {@code toCurrency},
     * using the reporting date. Returns the original amount if currencies are the same.
     */
    private BigDecimal convertSafe(BigDecimal amount, String fromCurrency, String toCurrency,
                                   ConsolidationReportRequest request, Long tenantId) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        // CurrencyService handles same-currency case by returning original amount
        return currencyService.convert(amount, fromCurrency, toCurrency, request.getReportingDate(), tenantId)
                .getConvertedAmount()
                .setScale(SCALE, ROUNDING);
    }
}
