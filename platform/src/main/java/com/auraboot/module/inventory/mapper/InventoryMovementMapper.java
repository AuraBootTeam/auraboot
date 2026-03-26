package com.auraboot.module.inventory.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * MyBatis mapper for biz_inventory_movement table.
 * All queries scope by tenant_id (TenantLineInterceptor adds it automatically
 * for standard queries; we add it explicitly in @Select raw SQL per convention).
 */
@Mapper
public interface InventoryMovementMapper {

    @Insert("""
            INSERT INTO biz_inventory_movement
                (pid, tenant_id, movement_type, product_pid, warehouse_pid,
                 quantity, unit_cost, reference_type, reference_pid, note,
                 moved_at, created_by, created_at)
            VALUES
                (#{pid}, #{tenantId}, #{movementType}, #{productPid}, #{warehousePid},
                 #{quantity}, #{unitCost}, #{referenceType}, #{referencePid}, #{note},
                 CURRENT_TIMESTAMP, #{createdBy}, CURRENT_TIMESTAMP)
            """)
    void insert(@Param("pid") String pid,
                @Param("tenantId") Long tenantId,
                @Param("movementType") String movementType,
                @Param("productPid") String productPid,
                @Param("warehousePid") String warehousePid,
                @Param("quantity") BigDecimal quantity,
                @Param("unitCost") BigDecimal unitCost,
                @Param("referenceType") String referenceType,
                @Param("referencePid") String referencePid,
                @Param("note") String note,
                @Param("createdBy") Long createdBy);

    /**
     * Returns the net quantity (SUM) for a product, optionally scoped to a warehouse.
     */
    @Select("""
            SELECT COALESCE(SUM(quantity), 0)
            FROM biz_inventory_movement
            WHERE tenant_id = #{tenantId}
              AND product_pid = #{productPid}
              AND (#{warehousePid} IS NULL OR warehouse_pid = #{warehousePid})
            """)
    BigDecimal sumQuantity(@Param("tenantId") Long tenantId,
                           @Param("productPid") String productPid,
                           @Param("warehousePid") String warehousePid);

    /**
     * Returns paginated movement history for a product, newest first.
     * warehousePid = null means all warehouses.
     */
    @Select("""
            SELECT pid, movement_type, product_pid, warehouse_pid,
                   quantity, unit_cost, reference_type, reference_pid,
                   note, moved_at, created_by
            FROM biz_inventory_movement
            WHERE tenant_id = #{tenantId}
              AND product_pid = #{productPid}
              AND (#{warehousePid} IS NULL OR warehouse_pid = #{warehousePid})
            ORDER BY moved_at DESC
            LIMIT #{pageSize} OFFSET #{offset}
            """)
    List<Map<String, Object>> listByProduct(@Param("tenantId") Long tenantId,
                                            @Param("productPid") String productPid,
                                            @Param("warehousePid") String warehousePid,
                                            @Param("pageSize") int pageSize,
                                            @Param("offset") int offset);

    @Select("""
            SELECT COUNT(*)
            FROM biz_inventory_movement
            WHERE tenant_id = #{tenantId}
              AND product_pid = #{productPid}
              AND (#{warehousePid} IS NULL OR warehouse_pid = #{warehousePid})
            """)
    long countByProduct(@Param("tenantId") Long tenantId,
                        @Param("productPid") String productPid,
                        @Param("warehousePid") String warehousePid);
}
