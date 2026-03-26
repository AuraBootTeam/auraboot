package com.auraboot.module.inventory.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.module.inventory.dto.InventoryMovementRequest;
import com.auraboot.module.inventory.service.InventoryMovementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * REST controller for the inventory movement ledger.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/inventory/movements} — paginated movement history for a product</li>
 *   <li>{@code POST /api/inventory/movements} — record a manual adjustment</li>
 *   <li>{@code GET /api/inventory/stock} — current stock level for a product</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
@Tag(name = "Inventory Movements", description = "Event-sourced inventory movement ledger")
public class InventoryMovementController {

    private final InventoryMovementService service;

    /**
     * Get paginated movement history for a product.
     *
     * @param productPid   required — product identifier
     * @param warehousePid optional — filter to a specific warehouse
     * @param pageNum      page number (default 1)
     * @param pageSize     rows per page (default 50, max 500)
     */
    @GetMapping("/movements")
    @Operation(summary = "Get movement history",
               description = "Paginated inventory movement history for a product")
    public ApiResponse<PaginationResult<Map<String, Object>>> listMovements(
            @Parameter(description = "Product PID") @RequestParam String productPid,
            @Parameter(description = "Warehouse PID (optional)") @RequestParam(required = false) String warehousePid,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "50") int pageSize) {

        PaginationResult<Map<String, Object>> result =
                service.getMovementHistory(productPid, warehousePid, pageNum, pageSize);
        return ApiResponse.success(result);
    }

    /**
     * Record a manual inventory adjustment.
     * Use movementType = ADJUSTMENT for stock corrections,
     * IN for manual receipts, OUT for manual issues.
     */
    @PostMapping("/movements")
    @Operation(summary = "Record manual movement",
               description = "Record a manual inventory movement (adjustment, manual IN/OUT)")
    public ApiResponse<Map<String, String>> recordMovement(
            @RequestBody InventoryMovementRequest request) {

        if (request.getProductPid() == null || request.getProductPid().isBlank()) {
            return ApiResponse.error("productPid is required");
        }
        if (request.getMovementType() == null || request.getMovementType().isBlank()) {
            return ApiResponse.error("movementType is required (IN, OUT, TRANSFER, ADJUSTMENT)");
        }
        if (request.getQuantity() == null) {
            return ApiResponse.error("quantity is required");
        }
        if (request.getReferenceType() == null) {
            request.setReferenceType("manual");
        }

        String pid = service.record(request);
        return ApiResponse.success(Map.of("pid", pid));
    }

    /**
     * Get current stock level for a product (derived from SUM of all movements).
     *
     * @param productPid   required — product identifier
     * @param warehousePid optional — filter to a specific warehouse
     */
    @GetMapping("/stock")
    @Operation(summary = "Get current stock level",
               description = "Current on-hand quantity derived from movement event sum")
    public ApiResponse<Map<String, Object>> getStock(
            @Parameter(description = "Product PID") @RequestParam String productPid,
            @Parameter(description = "Warehouse PID (optional)") @RequestParam(required = false) String warehousePid) {

        BigDecimal stock = service.getCurrentStock(productPid, warehousePid);
        return ApiResponse.success(Map.of(
                "productPid", productPid,
                "warehousePid", warehousePid != null ? warehousePid : "all",
                "onHandQuantity", stock
        ));
    }
}
