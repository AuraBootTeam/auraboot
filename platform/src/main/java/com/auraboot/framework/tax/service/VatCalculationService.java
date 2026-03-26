package com.auraboot.framework.tax.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.tax.dto.VatCalculationResult;
import com.auraboot.framework.tax.mapper.TaxVatRateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Service for VAT calculation.
 * Looks up VAT rates from the tax_vat_rate model and calculates tax amounts.
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VatCalculationService {

    private final TaxVatRateMapper vatRateMapper;

    /**
     * Calculate VAT for a given amount using the specified rate code.
     *
     * @param amount      the pre-tax amount
     * @param vatRateCode the VAT rate code to look up
     * @return calculation result with tax amount and total
     */
    public VatCalculationResult calculateVat(BigDecimal amount, String vatRateCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Map<String, Object> rateRecord = vatRateMapper.findByCode(tenantId, vatRateCode);

        if (rateRecord == null) {
            throw new IllegalArgumentException("VAT rate not found: " + vatRateCode);
        }

        BigDecimal ratePct = toBigDecimal(rateRecord.get("tax_vr_rate_pct"));
        BigDecimal rateDecimal = ratePct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        BigDecimal vatAmount = amount.multiply(rateDecimal).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalAmount = amount.add(vatAmount);

        VatCalculationResult result = new VatCalculationResult();
        result.setAmount(amount);
        result.setVatRate(ratePct);
        result.setVatAmount(vatAmount);
        result.setTotalAmount(totalAmount);
        result.setVatRateCode(vatRateCode);

        log.debug("VAT calculated: amount={}, rate={}%, vat={}, total={}",
                amount, ratePct, vatAmount, totalAmount);

        return result;
    }

    /**
     * Calculate VAT for multiple invoice lines.
     * Applies rounding per line, then sums.
     *
     * @param lines list of maps with keys: amount (BigDecimal), vatRateCode (String)
     * @return list of calculation results, one per line
     */
    public List<VatCalculationResult> calculateLineVat(List<Map<String, Object>> lines) {
        List<VatCalculationResult> results = new ArrayList<>();
        for (Map<String, Object> line : lines) {
            BigDecimal amount = toBigDecimal(line.get("amount"));
            String vatRateCode = (String) line.get("vatRateCode");

            if (vatRateCode == null || vatRateCode.isBlank()) {
                // No VAT rate — zero tax
                VatCalculationResult zeroResult = new VatCalculationResult();
                zeroResult.setAmount(amount);
                zeroResult.setVatRate(BigDecimal.ZERO);
                zeroResult.setVatAmount(BigDecimal.ZERO);
                zeroResult.setTotalAmount(amount);
                zeroResult.setVatRateCode(null);
                results.add(zeroResult);
            } else {
                results.add(calculateVat(amount, vatRateCode));
            }
        }
        return results;
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(value.toString());
    }
}
