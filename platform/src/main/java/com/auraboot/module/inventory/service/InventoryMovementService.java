package com.auraboot.module.inventory.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.module.inventory.dto.InventoryMovementRequest;
import com.auraboot.module.inventory.mapper.InventoryMovementMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Inventory movement ledger service.
 *
 * <p>Implements the event-sourcing pattern for inventory:
 * every stock change is recorded as an immutable movement row in
 * {@code biz_inventory_movement}. Current stock is derived by
 * {@code SUM(quantity)} over all movements for a product/warehouse.
 *
 * <p>Movement types:
 * <ul>
 *   <li>IN — goods received (purchase receipt, return inbound)</li>
 *   <li>OUT — goods issued (sales shipment, consumption, scrap)</li>
 *   <li>TRANSFER — movement between warehouses (two rows: OUT from source, IN to dest)</li>
 *   <li>ADJUSTMENT — stock count correction (positive or negative)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryMovementService {

    private final InventoryMovementMapper mapper;

    /**
     * Record a single inventory movement.
     *
     * @param type          movement type (IN, OUT, TRANSFER, ADJUSTMENT)
     * @param productPid    product identifier
     * @param quantity      signed quantity: positive = stock increase, negative = decrease
     * @param warehousePid  warehouse (may be null for non-warehouse-tracked products)
     * @param referencePid  originating document PID (e.g. purchase order PID)
     * @param referenceType originating document type (e.g. PURCHASE_ORDER)
     * @return generated PID for the movement row
     */
    @Transactional
    public String record(String type, String productPid, BigDecimal quantity,
                         String warehousePid, String referencePid, String referenceType) {
        String pid = UniqueIdGenerator.generate();
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();

        mapper.insert(pid, tenantId, type, productPid, warehousePid,
                quantity, null, referenceType, referencePid, null, userId);

        log.info("Inventory movement recorded: pid={} type={} product={} qty={} warehouse={}",
                pid, type, productPid, quantity, warehousePid);
        return pid;
    }

    /**
     * Record a movement from a full request DTO (used by manual adjustment API).
     */
    @Transactional
    public String record(InventoryMovementRequest request) {
        String pid = UniqueIdGenerator.generate();
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();

        mapper.insert(pid, tenantId,
                request.getMovementType(),
                request.getProductPid(),
                request.getWarehousePid(),
                request.getQuantity(),
                request.getUnitCost(),
                request.getReferenceType(),
                request.getReferencePid(),
                request.getNote(),
                userId);

        log.info("Manual inventory movement: pid={} type={} product={} qty={}",
                pid, request.getMovementType(), request.getProductPid(), request.getQuantity());
        return pid;
    }

    /**
     * Calculate current stock for a product, optionally scoped to a warehouse.
     * Returns 0 if no movements exist.
     *
     * @param productPid   product identifier
     * @param warehousePid warehouse filter (null = all warehouses)
     * @return net quantity on hand
     */
    public BigDecimal getCurrentStock(String productPid, String warehousePid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        BigDecimal stock = mapper.sumQuantity(tenantId, productPid, warehousePid);
        return stock != null ? stock : BigDecimal.ZERO;
    }

    /**
     * Retrieve paginated movement history for a product.
     *
     * @param productPid   product filter (required)
     * @param warehousePid warehouse filter (null = all warehouses)
     * @param pageNum      1-based page number
     * @param pageSize     rows per page (max 500)
     */
    public PaginationResult<Map<String, Object>> getMovementHistory(
            String productPid, String warehousePid, int pageNum, int pageSize) {

        if (pageNum < 1) pageNum = 1;
        if (pageSize < 1 || pageSize > 500) pageSize = 50;

        Long tenantId = MetaContext.getCurrentTenantId();
        int offset = (pageNum - 1) * pageSize;

        List<Map<String, Object>> rows = mapper.listByProduct(tenantId, productPid, warehousePid, pageSize, offset);
        long total = mapper.countByProduct(tenantId, productPid, warehousePid);

        return PaginationResult.of(rows, total, pageNum, pageSize);
    }
}
