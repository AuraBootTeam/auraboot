package com.auraboot.framework.currency.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.currency.dao.entity.ExchangeRate;
import com.auraboot.framework.currency.dao.mapper.ExchangeRateMapper;
import com.auraboot.framework.currency.dto.ConversionResult;
import com.auraboot.framework.currency.dto.ExchangeRateRequest;
import com.auraboot.framework.currency.dto.ExchangeRateResponse;
import com.auraboot.framework.currency.service.CurrencyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Implementation of CurrencyService providing exchange rate management
 * and currency conversion with triangulation support.
 */
@Slf4j
@Service
public class CurrencyServiceImpl implements CurrencyService {

    /** Pivot currency used for triangulated conversions */
    private static final String PIVOT_CURRENCY = "usd";

    private static final int CONVERSION_SCALE = 8;

    /** Common ISO 4217 currency codes */
    private static final List<String> SUPPORTED_CURRENCIES = Arrays.asList(
            "usd", "eur", "gbp", "jpy", "cny", "aud", "cad", "chf",
            "hkd", "sgd", "krw", "inr", "brl", "mxn", "zar", "sek",
            "nok", "dkk", "nzd", "thb", "twd", "myr", "idr", "php",
            "vnd", "aed", "sar", "rub", "try", "pln", "czk", "huf"
    );

    @Autowired
    private ExchangeRateMapper exchangeRateMapper;

    @Override
    public ConversionResult convert(BigDecimal amount, String fromCurrency, String toCurrency,
                                    LocalDate date, Long tenantId) {
        if (fromCurrency.equalsIgnoreCase(toCurrency)) {
            return new ConversionResult(amount, fromCurrency, amount, toCurrency,
                    BigDecimal.ONE, date != null ? date : LocalDate.now(), false);
        }

        LocalDate effectiveDate = date != null ? date : LocalDate.now();

        // Try direct rate first
        ExchangeRate directRate = exchangeRateMapper.findLatestRate(
                tenantId, normalizeCurrency(fromCurrency), normalizeCurrency(toCurrency), effectiveDate);

        if (directRate != null) {
            BigDecimal converted = amount.multiply(directRate.getRate())
                    .setScale(CONVERSION_SCALE, RoundingMode.HALF_UP);
            return new ConversionResult(amount, fromCurrency, converted, toCurrency,
                    directRate.getRate(), directRate.getEffectiveDate(), false);
        }

        // Try reverse rate (target -> base, then invert)
        ExchangeRate reverseRate = exchangeRateMapper.findLatestRate(
                tenantId, normalizeCurrency(toCurrency), normalizeCurrency(fromCurrency), effectiveDate);

        if (reverseRate != null) {
            BigDecimal invertedRate = BigDecimal.ONE.divide(reverseRate.getRate(), CONVERSION_SCALE, RoundingMode.HALF_UP);
            BigDecimal converted = amount.multiply(invertedRate)
                    .setScale(CONVERSION_SCALE, RoundingMode.HALF_UP);
            return new ConversionResult(amount, fromCurrency, converted, toCurrency,
                    invertedRate, reverseRate.getEffectiveDate(), false);
        }

        // Try triangulated conversion via pivot currency (USD)
        return tryTriangulatedConversion(amount, fromCurrency, toCurrency, effectiveDate, tenantId);
    }

    private ConversionResult tryTriangulatedConversion(BigDecimal amount, String fromCurrency,
                                                       String toCurrency, LocalDate date, Long tenantId) {
        // from -> pivot
        BigDecimal fromToPivotRate = findEffectiveRate(tenantId, fromCurrency, PIVOT_CURRENCY, date);
        if (fromToPivotRate == null) {
            throw new RuntimeException("No exchange rate found for " + fromCurrency + " -> " + PIVOT_CURRENCY
                    + " on or before " + date);
        }

        // pivot -> to
        BigDecimal pivotToTargetRate = findEffectiveRate(tenantId, PIVOT_CURRENCY, toCurrency, date);
        if (pivotToTargetRate == null) {
            throw new RuntimeException("No exchange rate found for " + PIVOT_CURRENCY + " -> " + toCurrency
                    + " on or before " + date);
        }

        BigDecimal compositeRate = fromToPivotRate.multiply(pivotToTargetRate)
                .setScale(CONVERSION_SCALE, RoundingMode.HALF_UP);
        BigDecimal converted = amount.multiply(compositeRate)
                .setScale(CONVERSION_SCALE, RoundingMode.HALF_UP);

        log.info("Triangulated conversion: {} {} -> {} {} via {} (rate={})",
                amount, fromCurrency, converted, toCurrency, PIVOT_CURRENCY, compositeRate);

        return new ConversionResult(amount, fromCurrency, converted, toCurrency,
                compositeRate, date, true);
    }

    /**
     * Find the effective rate for a pair, checking both direct and inverse.
     */
    private BigDecimal findEffectiveRate(Long tenantId, String base, String target, LocalDate date) {
        if (base.equalsIgnoreCase(target)) {
            return BigDecimal.ONE;
        }
        ExchangeRate direct = exchangeRateMapper.findLatestRate(
                tenantId, normalizeCurrency(base), normalizeCurrency(target), date);
        if (direct != null) {
            return direct.getRate();
        }
        ExchangeRate reverse = exchangeRateMapper.findLatestRate(
                tenantId, normalizeCurrency(target), normalizeCurrency(base), date);
        if (reverse != null) {
            return BigDecimal.ONE.divide(reverse.getRate(), CONVERSION_SCALE, RoundingMode.HALF_UP);
        }
        return null;
    }

    @Override
    public ExchangeRateResponse getLatestRate(String baseCurrency, String targetCurrency, Long tenantId) {
        ExchangeRate rate = exchangeRateMapper.findLatestRate(
                tenantId, normalizeCurrency(baseCurrency), normalizeCurrency(targetCurrency), LocalDate.now());
        if (rate == null) {
            throw new RuntimeException("No exchange rate found for " + baseCurrency + " -> " + targetCurrency);
        }
        return toResponse(rate);
    }

    @Override
    @Transactional
    public ExchangeRateResponse saveRate(ExchangeRateRequest request, Long tenantId, Long userId) {
        String baseCcy = normalizeCurrency(request.getBaseCurrency());
        String targetCcy = normalizeCurrency(request.getTargetCurrency());

        if (baseCcy.equals(targetCcy)) {
            throw new RuntimeException("Base and target currency must be different");
        }

        // Check if rate already exists for this pair + date
        QueryWrapper<ExchangeRate> qw = new QueryWrapper<>();
        qw.eq("tenant_id", tenantId)
                .eq("base_currency", baseCcy)
                .eq("target_currency", targetCcy)
                .eq("effective_date", request.getEffectiveDate())
                .eq("deleted_flag", false);
        ExchangeRate existing = exchangeRateMapper.selectOne(qw);

        ExchangeRate entity;
        if (existing != null) {
            // Update existing rate
            existing.setRate(request.getRate());
            existing.setSource(request.getSource() != null ? request.getSource() : "manual");
            existing.setUpdatedAt(Instant.now());
            existing.setUpdatedBy(userId);
            exchangeRateMapper.updateById(existing);
            entity = existing;
            log.info("Updated exchange rate: {} -> {} = {} on {}",
                    baseCcy, targetCcy, request.getRate(), request.getEffectiveDate());
        } else {
            entity = new ExchangeRate();
            entity.setPid(UniqueIdGenerator.generate());
            entity.setTenantId(tenantId);
            entity.setBaseCurrency(baseCcy);
            entity.setTargetCurrency(targetCcy);
            entity.setRate(request.getRate());
            entity.setEffectiveDate(request.getEffectiveDate());
            entity.setSource(request.getSource() != null ? request.getSource() : "manual");
            entity.setCreatedAt(Instant.now());
            entity.setUpdatedAt(Instant.now());
            entity.setCreatedBy(userId);
            entity.setDeletedFlag(false);
            exchangeRateMapper.insert(entity);
            log.info("Created exchange rate: {} -> {} = {} on {}",
                    baseCcy, targetCcy, request.getRate(), request.getEffectiveDate());
        }

        return toResponse(entity);
    }

    @Override
    @Transactional
    public void deleteRate(String pid, Long tenantId) {
        QueryWrapper<ExchangeRate> qw = new QueryWrapper<>();
        qw.eq("pid", pid)
                .eq("tenant_id", tenantId)
                .eq("deleted_flag", false);
        ExchangeRate rate = exchangeRateMapper.selectOne(qw);
        if (rate == null) {
            throw new RuntimeException("Exchange rate not found: " + pid);
        }
        // Use deleteById which MyBatis Plus translates to
        // UPDATE SET deleted_flag=true WHERE id=? for logic-delete entities
        exchangeRateMapper.deleteById(rate.getId());
        log.info("Deleted exchange rate: {}", pid);
    }

    @Override
    public List<ExchangeRateResponse> listRates(Long tenantId, String baseCurrency, LocalDate date) {
        if (baseCurrency != null && date != null) {
            // Filter by base currency and date
            QueryWrapper<ExchangeRate> qw = new QueryWrapper<>();
            qw.eq("tenant_id", tenantId)
                    .eq("base_currency", normalizeCurrency(baseCurrency))
                    .eq("effective_date", date)
                    .eq("deleted_flag", false)
                    .orderByAsc("target_currency");
            return exchangeRateMapper.selectList(qw).stream()
                    .map(this::toResponse)
                    .collect(Collectors.toList());
        } else if (baseCurrency != null) {
            return exchangeRateMapper.findByBaseCurrency(tenantId, normalizeCurrency(baseCurrency))
                    .stream().map(this::toResponse).collect(Collectors.toList());
        } else if (date != null) {
            return exchangeRateMapper.findByEffectiveDate(tenantId, date)
                    .stream().map(this::toResponse).collect(Collectors.toList());
        } else {
            return listLatestRates(tenantId);
        }
    }

    @Override
    public List<ExchangeRateResponse> listLatestRates(Long tenantId) {
        return exchangeRateMapper.findAllLatestRates(tenantId)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Override
    public List<String> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }

    private ExchangeRateResponse toResponse(ExchangeRate entity) {
        ExchangeRateResponse resp = new ExchangeRateResponse();
        resp.setPid(entity.getPid());
        resp.setBaseCurrency(entity.getBaseCurrency());
        resp.setTargetCurrency(entity.getTargetCurrency());
        resp.setRate(entity.getRate());
        resp.setEffectiveDate(entity.getEffectiveDate());
        resp.setSource(entity.getSource());
        resp.setCreatedAt(entity.getCreatedAt());
        resp.setUpdatedAt(entity.getUpdatedAt());
        return resp;
    }

    private String normalizeCurrency(String currency) {
        return currency == null ? null : currency.toLowerCase(Locale.ROOT);
    }
}
