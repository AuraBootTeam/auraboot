package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.entity.EdiMessageType;
import com.auraboot.framework.meta.entity.EdiPartner;
import com.auraboot.framework.meta.entity.EdiTransaction;
import com.auraboot.framework.meta.service.impl.EdiService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for EDI/cXML integration.
 *
 * <p>Provides endpoints for managing trading partners, message type definitions,
 * and sending/receiving EDI documents. Supports EDI X12, EDIFACT, cXML, and
 * custom XML/JSON protocols.
 *
 * @since 5.3.0
 */
@RestController
@RequestMapping("/api/edi")
@RequiredArgsConstructor
public class EdiController {

    private final EdiService ediService;

    // ==================== Partner Management ====================

    /**
     * List all EDI partners.
     * GET /api/edi/partners
     */
    @GetMapping("/partners")
    @RequirePermission(MetaPermission.EDI_READ)
    public ApiResponse<List<EdiPartner>> listPartners() {
        return ApiResponse.success(ediService.listPartners());
    }

    /**
     * Get an EDI partner by ID.
     * GET /api/edi/partners/{id}
     */
    @GetMapping("/partners/{id}")
    @RequirePermission(MetaPermission.EDI_READ)
    public ApiResponse<EdiPartner> getPartner(@PathVariable Long id) {
        return ApiResponse.success(ediService.getPartner(id));
    }

    /**
     * Create a new EDI partner.
     * POST /api/edi/partners
     */
    @PostMapping("/partners")
    @RequirePermission(MetaPermission.EDI_MANAGE)
    public ApiResponse<EdiPartner> createPartner(@RequestBody EdiPartner partner) {
        return ApiResponse.success(ediService.createPartner(partner));
    }

    /**
     * Update an EDI partner.
     * PUT /api/edi/partners/{id}
     */
    @PutMapping("/partners/{id}")
    @RequirePermission(MetaPermission.EDI_MANAGE)
    public ApiResponse<EdiPartner> updatePartner(@PathVariable Long id, @RequestBody EdiPartner partner) {
        return ApiResponse.success(ediService.updatePartner(id, partner));
    }

    /**
     * Delete an EDI partner (soft delete).
     * DELETE /api/edi/partners/{id}
     */
    @DeleteMapping("/partners/{id}")
    @RequirePermission(MetaPermission.EDI_MANAGE)
    public ApiResponse<Map<String, Object>> deletePartner(@PathVariable Long id) {
        ediService.deletePartner(id);
        return ApiResponse.success(Map.of("success", true, "id", id));
    }

    // ==================== Message Type Management ====================

    /**
     * List all EDI message types.
     * GET /api/edi/message-types
     */
    @GetMapping("/message-types")
    @RequirePermission(MetaPermission.EDI_READ)
    public ApiResponse<List<EdiMessageType>> listMessageTypes() {
        return ApiResponse.success(ediService.listMessageTypes());
    }

    /**
     * Get an EDI message type by ID.
     * GET /api/edi/message-types/{id}
     */
    @GetMapping("/message-types/{id}")
    @RequirePermission(MetaPermission.EDI_READ)
    public ApiResponse<EdiMessageType> getMessageType(@PathVariable Long id) {
        return ApiResponse.success(ediService.getMessageType(id));
    }

    /**
     * Create a new EDI message type.
     * POST /api/edi/message-types
     */
    @PostMapping("/message-types")
    @RequirePermission(MetaPermission.EDI_MANAGE)
    public ApiResponse<EdiMessageType> createMessageType(@RequestBody EdiMessageType messageType) {
        return ApiResponse.success(ediService.createMessageType(messageType));
    }

    /**
     * Update an EDI message type.
     * PUT /api/edi/message-types/{id}
     */
    @PutMapping("/message-types/{id}")
    @RequirePermission(MetaPermission.EDI_MANAGE)
    public ApiResponse<EdiMessageType> updateMessageType(@PathVariable Long id,
                                                          @RequestBody EdiMessageType messageType) {
        return ApiResponse.success(ediService.updateMessageType(id, messageType));
    }

    /**
     * Delete an EDI message type (soft delete).
     * DELETE /api/edi/message-types/{id}
     */
    @DeleteMapping("/message-types/{id}")
    @RequirePermission(MetaPermission.EDI_MANAGE)
    public ApiResponse<Map<String, Object>> deleteMessageType(@PathVariable Long id) {
        ediService.deleteMessageType(id);
        return ApiResponse.success(Map.of("success", true, "id", id));
    }

    // ==================== Transaction History ====================

    /**
     * Get EDI transactions with optional filters.
     * GET /api/edi/transactions?partnerId=1&status=COMPLETED&limit=50
     */
    @GetMapping("/transactions")
    @RequirePermission(MetaPermission.EDI_READ)
    public ApiResponse<List<EdiTransaction>> listTransactions(
            @RequestParam(required = false) Long partnerId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        if (partnerId != null) {
            return ApiResponse.success(ediService.getTransactionHistory(partnerId, status, limit));
        }
        if (status != null) {
            return ApiResponse.success(ediService.getTransactionsByStatus(status, limit));
        }
        return ApiResponse.success(ediService.getTransactionsByStatus(null, limit));
    }

    /**
     * Get a single transaction by transaction number.
     * GET /api/edi/transactions/{transactionNo}
     */
    @GetMapping("/transactions/{transactionNo}")
    @RequirePermission(MetaPermission.EDI_READ)
    public ApiResponse<EdiTransaction> getTransaction(@PathVariable String transactionNo) {
        return ApiResponse.success(ediService.getTransaction(transactionNo));
    }

    // ==================== Send / Receive ====================

    /**
     * Send an outbound EDI message.
     * POST /api/edi/send
     * Body: { "partnerId": 1, "messageTypeCode": "edi_856", "data": { ... } }
     */
    @PostMapping("/send")
    @RequirePermission(MetaPermission.EDI_MANAGE)
    @SuppressWarnings("unchecked")
    public ApiResponse<EdiTransaction> sendMessage(@RequestBody Map<String, Object> body) {
        Long partnerId = ((Number) body.get("partnerId")).longValue();
        String messageTypeCode = (String) body.get("messageTypeCode");
        Map<String, Object> data = (Map<String, Object>) body.get("data");

        if (data == null) {
            data = Map.of();
        }

        return ApiResponse.success(ediService.sendMessage(partnerId, messageTypeCode, data));
    }

    /**
     * Receive an inbound EDI message (webhook endpoint).
     * POST /api/edi/receive
     * Body: { "partnerId": 1, "rawContent": "ISA*00*..." }
     */
    @PostMapping("/receive")
    @RequirePermission(MetaPermission.EDI_MANAGE)
    public ApiResponse<EdiTransaction> receiveMessage(@RequestBody Map<String, Object> body) {
        Long partnerId = ((Number) body.get("partnerId")).longValue();
        String rawContent = (String) body.get("rawContent");

        return ApiResponse.success(ediService.receiveMessage(partnerId, rawContent));
    }

    /**
     * Retry a failed transaction.
     * POST /api/edi/transactions/{transactionNo}/retry
     */
    @PostMapping("/transactions/{transactionNo}/retry")
    @RequirePermission(MetaPermission.EDI_MANAGE)
    public ApiResponse<EdiTransaction> retryTransaction(@PathVariable String transactionNo) {
        return ApiResponse.success(ediService.retryTransaction(transactionNo));
    }
}
