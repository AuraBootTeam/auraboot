package com.auraboot.module.finance.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.module.finance.dto.PostJournalRequest;
import com.auraboot.module.finance.engine.GlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * REST controller for the General Ledger module.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/finance/gl/journal} — post a balanced double-entry journal</li>
 *   <li>{@code GET  /api/finance/gl/trial-balance} — trial balance for a fiscal period</li>
 *   <li>{@code GET  /api/finance/gl/ledger} — paginated GL detail for an account</li>
 *   <li>{@code GET  /api/finance/gl/balance} — debit/credit/net balance for an account</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/finance/gl")
@RequiredArgsConstructor
@Tag(name = "General Ledger", description = "Double-entry GL journal entries and reporting")
public class GlController {

    private final GlService glService;

    /**
     * Post a balanced double-entry journal entry.
     * The request must contain at least two lines and total debits must equal total credits.
     */
    @PostMapping("/journal")
    @Operation(summary = "Post journal entry",
               description = "Post a balanced double-entry journal; returns the journal PID")
    public ApiResponse<Map<String, String>> postJournal(@RequestBody PostJournalRequest request) {
        if (request.getEntries() == null || request.getEntries().size() < 2) {
            return ApiResponse.error("A journal entry requires at least two lines");
        }
        try {
            String journalPid = glService.postJournalEntry(request.getEntries(), request.getDescription());
            return ApiResponse.success(Map.of("journalPid", journalPid));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * Retrieve trial balance for a fiscal period.
     *
     * @param period fiscal period in YYYY-MM format (e.g. "2026-03")
     */
    @GetMapping("/trial-balance")
    @Operation(summary = "Get trial balance",
               description = "Aggregated debit/credit/net per account for a fiscal period")
    public ApiResponse<List<Map<String, Object>>> trialBalance(
            @Parameter(description = "Fiscal period YYYY-MM") @RequestParam String period) {

        List<Map<String, Object>> rows = glService.getTrialBalance(period);
        return ApiResponse.success(rows);
    }

    /**
     * Get account balance summary for a date range.
     *
     * @param accountCode chart-of-accounts code
     * @param from        start date (ISO format yyyy-MM-dd)
     * @param to          end date (ISO format yyyy-MM-dd)
     */
    @GetMapping("/balance")
    @Operation(summary = "Get account balance",
               description = "Debit/credit/net balance for an account within a date range")
    public ApiResponse<Map<String, Object>> accountBalance(
            @Parameter(description = "Account code") @RequestParam String accountCode,
            @Parameter(description = "From date (yyyy-MM-dd)") @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "To date (yyyy-MM-dd)") @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        Map<String, Object> balance = glService.getAccountBalance(accountCode, from, to);
        return ApiResponse.success(balance);
    }

    /**
     * Retrieve paginated general ledger detail for an account.
     *
     * @param accountCode account code to drill into
     * @param from        start date (ISO format yyyy-MM-dd)
     * @param to          end date (ISO format yyyy-MM-dd)
     * @param pageNum     page number (default 1)
     * @param pageSize    rows per page (default 50)
     */
    @GetMapping("/ledger")
    @Operation(summary = "Get general ledger detail",
               description = "Paginated GL entry lines for an account between two dates")
    public ApiResponse<PaginationResult<Map<String, Object>>> generalLedger(
            @Parameter(description = "Account code") @RequestParam String accountCode,
            @Parameter(description = "From date (yyyy-MM-dd)") @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "To date (yyyy-MM-dd)") @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "50") int pageSize) {

        PaginationResult<Map<String, Object>> result =
                glService.getGeneralLedger(accountCode, from, to, pageNum, pageSize);
        return ApiResponse.success(result);
    }
}
