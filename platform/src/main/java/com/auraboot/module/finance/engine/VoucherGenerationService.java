package com.auraboot.module.finance.engine;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.TenantClock;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.service.DynamicDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Core service that takes a matched voucher template + business payload
 * and generates a journal entry (header + lines) in the database.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoucherGenerationService {

    private final VoucherTemplateResolver templateResolver;
    private final DynamicDataService dynamicDataService;
    private final TenantClock tenantClock;

    /**
     * Generate a journal entry from a voucher template and business payload.
     *
     * @param template      The voucher template record (from fac_voucher_template)
     * @param templateLines The template line records (from fac_voucher_template_line)
     * @param payload       The business event payload
     * @param sourceType    Source model code (e.g., "pe_warehouse_out")
     * @param sourceId      Source record ID
     * @param sourceCode    Source document code (e.g., "WO-20260223-001")
     * @return Created journal entry ID (pid), or null if generation skipped
     */
    public String generateVoucher(Map<String, Object> template,
                                   List<Map<String, Object>> templateLines,
                                   Map<String, Object> payload,
                                   String sourceType, String sourceId, String sourceCode) {
        if (templateLines == null || templateLines.isEmpty()) {
            log.info("Skipping voucher generation: no template lines");
            return null;
        }

        // 1. Resolve each template line
        List<Map<String, Object>> resolvedLines = new ArrayList<>();
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;

        for (Map<String, Object> tplLine : templateLines) {
            String amountExpr = (String) tplLine.get("fac_vtl_amount_expr");
            BigDecimal amount = templateResolver.resolveAmount(amountExpr, payload);

            if (amount.compareTo(BigDecimal.ZERO) == 0) {
                continue; // Skip zero-amount lines
            }

            String direction = (String) tplLine.get("fac_vtl_direction");
            String accountCode = (String) tplLine.get("fac_vtl_account_code");
            String descExpr = (String) tplLine.get("fac_vtl_description_expr");
            String description = descExpr != null ? templateResolver.resolveString(descExpr, payload) : "";

            // Resolve account ID from account code
            String accountId = resolveAccountId(accountCode);

            Map<String, Object> line = new HashMap<>();
            line.put("fac_jel_account_id", accountId);
            line.put("fac_jel_description", description);

            if ("debit".equals(direction)) {
                line.put("fac_jel_debit", amount);
                line.put("fac_jel_credit", BigDecimal.ZERO);
                totalDebit = totalDebit.add(amount);
            } else {
                line.put("fac_jel_debit", BigDecimal.ZERO);
                line.put("fac_jel_credit", amount);
                totalCredit = totalCredit.add(amount);
            }

            resolvedLines.add(line);
        }

        // 2. Check for empty result
        if (resolvedLines.isEmpty()) {
            log.info("Skipping voucher generation: all lines resolved to zero amount");
            return null;
        }

        // 3. Validate debit == credit
        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new IllegalStateException(
                "Journal entry is not balanced: debit=" + totalDebit + " credit=" + totalCredit);
        }

        // 4. Create journal entry header
        Map<String, Object> jeData = new HashMap<>();
        jeData.put("fac_je_code", generateJournalEntryCode());
        jeData.put("fac_je_date", tenantClock.businessDate(MetaContext.getCurrentTenantId()).toString());
        jeData.put("fac_je_source_type", sourceType);
        jeData.put("fac_je_source_id", sourceId);
        jeData.put("fac_je_source_code", sourceCode);
        jeData.put("fac_je_template_id", resolveId(template));
        jeData.put("fac_je_description", buildDescription(template, payload));
        jeData.put("fac_je_total_debit", totalDebit);
        jeData.put("fac_je_total_credit", totalCredit);
        jeData.put("fac_je_status", "draft");

        Map<String, Object> createdJe = dynamicDataService.create("fac_journal_entry", jeData);
        String jeId = resolveId(createdJe);

        // 5. Create journal entry lines
        for (Map<String, Object> line : resolvedLines) {
            line.put("fac_jel_entry_id", jeId);
        }
        dynamicDataService.batchCreate("fac_journal_entry_line", resolvedLines);

        log.info("Generated journal entry {} with {} lines (debit={}, credit={})",
            jeData.get("fac_je_code"), resolvedLines.size(), totalDebit, totalCredit);

        return jeId;
    }

    /**
     * Resolve account ID from account code by querying fac_account.
     * Logs a warning and returns the code as-is if the account is not found.
     */
    String resolveAccountId(String accountCode) {
        if (accountCode == null) return null;
        try {
            DynamicQueryRequest query = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(1)
                .conditions(List.of(
                    QueryCondition.builder()
                        .fieldName("fac_acc_code")
                        .operator(QueryCondition.Operator.EQ)
                        .value(accountCode)
                        .build()
                ))
                .build();
            PaginationResult<Map<String, Object>> result = dynamicDataService.list("fac_account", query);
            if (result != null && result.getRecords() != null && !result.getRecords().isEmpty()) {
                return resolveId(result.getRecords().get(0));
            }
            log.warn("Account not found for code '{}', using code as fallback ID", accountCode);
        } catch (Exception e) {
            log.warn("Failed to resolve account by code '{}': {}", accountCode, e.getMessage());
        }
        return accountCode; // Fallback: return code as-is
    }

    String generateJournalEntryCode() {
        return "JE-" + tenantClock.businessDate(MetaContext.getCurrentTenantId()).format(DateTimeFormatter.ofPattern("yyyyMMdd"))
            + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    }

    private String buildDescription(Map<String, Object> template, Map<String, Object> payload) {
        String templateName = template != null ? (String) template.get("fac_vt_name") : "";
        return templateName != null ? templateName : "";
    }

    static String resolveId(Map<String, Object> record) {
        if (record == null) return null;
        Object pid = record.get("pid");
        if (pid != null) return pid.toString();
        Object id = record.get("id");
        return id != null ? id.toString() : null;
    }
}
