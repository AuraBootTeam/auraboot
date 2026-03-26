package com.auraboot.module.mrp.adapter;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.module.mrp.port.InventoryQueryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@Primary
@RequiredArgsConstructor
@Slf4j
public class DynamicTableInventoryAdapter implements InventoryQueryPort {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public BigDecimal getOnHandQty(Long materialId, Long warehouseId) {
        try {
            if (warehouseId != null) {
                String sql = "SELECT COALESCE(pe_inv_qty, 0) FROM mt_pe_inventory " +
                    "WHERE pe_inv_product_id = ? AND pe_inv_warehouse_id = ? AND tenant_id = ?";
                return jdbcTemplate.queryForObject(sql, BigDecimal.class, materialId, warehouseId, getTenantId());
            }
            String sql = "SELECT COALESCE(SUM(pe_inv_qty), 0) FROM mt_pe_inventory " +
                "WHERE pe_inv_product_id = ? AND tenant_id = ?";
            BigDecimal result = jdbcTemplate.queryForObject(sql, BigDecimal.class, materialId, getTenantId());
            return result != null ? result : BigDecimal.ZERO;
        } catch (EmptyResultDataAccessException e) {
            return BigDecimal.ZERO;
        }
    }

    @Override
    public BigDecimal getInTransitQty(Long materialId) {
        try {
            String sql = "SELECT COALESCE(SUM(pe_po_line_qty), 0) FROM mt_pe_purchase_order_line " +
                "WHERE pe_po_line_product_id = ? AND tenant_id = ?";
            BigDecimal result = jdbcTemplate.queryForObject(sql, BigDecimal.class, materialId, getTenantId());
            return result != null ? result : BigDecimal.ZERO;
        } catch (EmptyResultDataAccessException e) {
            return BigDecimal.ZERO;
        }
    }

    @Override
    public BigDecimal getAllocatedQty(Long materialId) {
        try {
            // Sum of confirmed/in-progress sales order line quantities for this material
            String sql = "SELECT COALESCE(SUM(sol.pe_so_line_qty), 0) " +
                "FROM mt_pe_sales_order_line sol " +
                "JOIN mt_pe_sales_order so ON sol.pe_so_line_order_id = so.id AND so.tenant_id = ? " +
                "WHERE sol.pe_so_line_product_id = ? AND so.pe_so_status IN ('confirmed', 'in_progress') " +
                "AND sol.tenant_id = ?";
            BigDecimal result = jdbcTemplate.queryForObject(sql, BigDecimal.class, getTenantId(), materialId, getTenantId());
            return result != null ? result : BigDecimal.ZERO;
        } catch (EmptyResultDataAccessException e) {
            return BigDecimal.ZERO;
        } catch (Exception e) {
            log.debug("Could not calculate allocated qty for material {}, defaulting to 0", materialId);
            return BigDecimal.ZERO;
        }
    }

    @Override
    public BigDecimal getSafetyStock(Long materialId) {
        try {
            String sql = "SELECT COALESCE(MAX(pe_inv_safety_stock), 0) FROM mt_pe_inventory " +
                "WHERE pe_inv_product_id = ? AND tenant_id = ?";
            BigDecimal result = jdbcTemplate.queryForObject(sql, BigDecimal.class, materialId, getTenantId());
            return result != null ? result : BigDecimal.ZERO;
        } catch (EmptyResultDataAccessException e) {
            return BigDecimal.ZERO;
        }
    }

    @Override
    public BigDecimal getAvailableQty(Long materialId) {
        return getOnHandQty(materialId, null).subtract(getAllocatedQty(materialId));
    }

    private Long getTenantId() {
        return MetaContext.getCurrentTenantId();
    }
}
