package com.auraboot.framework.tax.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * Mapper for e-invoice data access from the dynamic entity table.
 */
@Mapper
public interface TaxEInvoiceMapper {

    @Select("SELECT * FROM mt_tax_einvoice " +
            "WHERE pid = #{pid} " +
            "AND tenant_id = #{tenantId}")
    Map<String, Object> findByPid(
            @Param("tenantId") Long tenantId,
            @Param("pid") String pid
    );

    @Select("SELECT * FROM mt_tax_einvoice_line " +
            "WHERE tax_eil_einvoice_id = #{einvoicePid} " +
            "AND tenant_id = #{tenantId} " +
            "ORDER BY tax_eil_line_no ASC")
    List<Map<String, Object>> findLinesByEInvoicePid(
            @Param("tenantId") Long tenantId,
            @Param("einvoicePid") String einvoicePid
    );

    @Update("UPDATE mt_tax_einvoice " +
            "SET tax_ei_status = #{status}, " +
            "    tax_ei_qr_code_data = #{qrCodeData}, " +
            "    tax_ei_issue_date = CURRENT_DATE, " +
            "    updated_at = NOW() " +
            "WHERE pid = #{pid} " +
            "AND tenant_id = #{tenantId}")
    int updateStatusAndQrCode(
            @Param("tenantId") Long tenantId,
            @Param("pid") String pid,
            @Param("status") String status,
            @Param("qrCodeData") String qrCodeData
    );

    @Select("SELECT * FROM mt_tax_einvoice " +
            "WHERE tax_ei_status = #{status} " +
            "AND tenant_id = #{tenantId} " +
            "ORDER BY created_at DESC")
    List<Map<String, Object>> findByStatus(
            @Param("tenantId") Long tenantId,
            @Param("status") String status
    );
}
