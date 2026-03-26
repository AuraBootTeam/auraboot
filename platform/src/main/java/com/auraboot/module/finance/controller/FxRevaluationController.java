package com.auraboot.module.finance.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.module.finance.engine.FxRevaluationService;
import com.auraboot.module.finance.engine.FxRevaluationService.RevaluationResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * REST controller for period-end foreign currency revaluation.
 *
 * <p>Endpoint: {@code POST /api/finance/fx-revaluation}
 *
 * <p>Query parameter {@code date} (optional, defaults to last day of current month)
 * specifies the closing date at which foreign-currency balances are retranslated.
 *
 * <p>Example:
 * <pre>
 *   POST /api/finance/fx-revaluation?date=2026-03-31
 * </pre>
 *
 * @author AuraBoot Team
 * @since 6.4.0
 */
@Slf4j
@RestController
@RequestMapping("/api/finance")
@RequiredArgsConstructor
@Tag(name = "FX Revaluation", description = "Period-end foreign currency revaluation (IFRS IAS 21 / GAAP ASC 830)")
public class FxRevaluationController {

    private final FxRevaluationService fxRevaluationService;

    /**
     * Run period-end FX revaluation for the current tenant.
     *
     * @param date reporting date (default: last day of current month)
     * @return revaluation summary: date, base currency, count adjusted, total adjustment
     */
    @PostMapping("/fx-revaluation")
    @Operation(
            summary = "Run period-end FX revaluation",
            description = "Retranslates all foreign-currency balances (AR/AP/Bank) at the closing "
                    + "exchange rate on the given date. Adjustments are recorded in "
                    + "fin_fx_revaluation_log. Returns a summary of the run."
    )
    public ApiResponse<RevaluationResult> runRevaluation(
            @Parameter(description = "Closing/reporting date (YYYY-MM-DD). Defaults to last day of current month.")
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date) {

        if (date == null) {
            date = YearMonth.now().atEndOfMonth();
        }

        log.info("FX revaluation triggered via API for date={}", date);
        RevaluationResult result = fxRevaluationService.revaluate(date);
        return ApiResponse.success(result);
    }
}
