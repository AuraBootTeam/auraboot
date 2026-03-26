package com.auraboot.framework.currency.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.auraboot.framework.currency.dao.entity.ExchangeRate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * Mapper for exchange rate data access.
 */
@Mapper
public interface ExchangeRateMapper extends BaseMapper<ExchangeRate> {

    /**
     * Find the latest rate for a currency pair on or before a given date.
     */
    @Select("SELECT * FROM ab_exchange_rate " +
            "WHERE tenant_id = #{tenantId} " +
            "AND base_currency = #{baseCurrency} " +
            "AND target_currency = #{targetCurrency} " +
            "AND effective_date <= #{date} " +
            "AND deleted_flag = FALSE " +
            "ORDER BY effective_date DESC LIMIT 1")
    ExchangeRate findLatestRate(
            @Param("tenantId") Long tenantId,
            @Param("baseCurrency") String baseCurrency,
            @Param("targetCurrency") String targetCurrency,
            @Param("date") LocalDate date
    );

    /**
     * Find all rates for a given effective date.
     */
    @Select("SELECT * FROM ab_exchange_rate " +
            "WHERE tenant_id = #{tenantId} " +
            "AND effective_date = #{date} " +
            "AND deleted_flag = FALSE " +
            "ORDER BY base_currency, target_currency")
    List<ExchangeRate> findByEffectiveDate(
            @Param("tenantId") Long tenantId,
            @Param("date") LocalDate date
    );

    /**
     * Find all rates for a specific base currency.
     */
    @Select("SELECT * FROM ab_exchange_rate " +
            "WHERE tenant_id = #{tenantId} " +
            "AND base_currency = #{baseCurrency} " +
            "AND deleted_flag = FALSE " +
            "ORDER BY effective_date DESC, target_currency")
    List<ExchangeRate> findByBaseCurrency(
            @Param("tenantId") Long tenantId,
            @Param("baseCurrency") String baseCurrency
    );

    /**
     * Find the latest rates (most recent effective_date) for all pairs.
     */
    @Select("SELECT DISTINCT ON (base_currency, target_currency) * " +
            "FROM ab_exchange_rate " +
            "WHERE tenant_id = #{tenantId} " +
            "AND deleted_flag = FALSE " +
            "ORDER BY base_currency, target_currency, effective_date DESC")
    List<ExchangeRate> findAllLatestRates(@Param("tenantId") Long tenantId);
}
