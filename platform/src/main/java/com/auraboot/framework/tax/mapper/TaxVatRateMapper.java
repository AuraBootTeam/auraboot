package com.auraboot.framework.tax.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * Mapper for VAT rate lookups from the dynamic entity table.
 * Note: mt_* tables are dynamic; we use @Select with raw SQL.
 */
@Mapper
public interface TaxVatRateMapper {

    @Select("SELECT * FROM mt_tax_vat_rate " +
            "WHERE tax_vr_code = #{code} " +
            "AND tenant_id = #{tenantId} " +
            "AND deleted_flag = FALSE " +
            "LIMIT 1")
    Map<String, Object> findByCode(
            @Param("tenantId") Long tenantId,
            @Param("code") String code
    );

    @Select("SELECT * FROM mt_tax_vat_rate " +
            "WHERE tax_vr_is_active = true " +
            "AND tenant_id = #{tenantId} " +
            "AND deleted_flag = FALSE " +
            "AND (tax_vr_expiry_date IS NULL OR tax_vr_expiry_date::date >= CURRENT_DATE) " +
            "AND tax_vr_effective_date::date <= CURRENT_DATE " +
            "ORDER BY tax_vr_rate_pct ASC")
    List<Map<String, Object>> findActiveRates(@Param("tenantId") Long tenantId);

    @Select("SELECT * FROM mt_tax_vat_rate " +
            "WHERE tax_vr_is_default = true " +
            "AND tax_vr_category = #{category} " +
            "AND tax_vr_is_active = true " +
            "AND tenant_id = #{tenantId} " +
            "AND deleted_flag = FALSE " +
            "LIMIT 1")
    Map<String, Object> findDefaultRate(
            @Param("tenantId") Long tenantId,
            @Param("category") String category
    );
}
