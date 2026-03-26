package com.auraboot.module.bitemporal.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.module.bitemporal.dto.BiTemporalRequest;
import com.auraboot.module.bitemporal.entity.BiTemporalRecord;
import com.auraboot.module.bitemporal.service.BiTemporalService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * REST controller for bi-temporal versioning operations.
 * Provides endpoints for creating, querying, correcting, and terminating
 * bi-temporal records.
 *
 * @since 6.0.0
 */
@RestController
@RequestMapping("/api/bitemporal")
@RequiredArgsConstructor
public class BiTemporalController {

    private final BiTemporalService biTemporalService;

    /**
     * Get the current version of an entity (valid now, latest transaction).
     */
    @GetMapping("/{entityType}/{entityId}/current")
    public ApiResponse<BiTemporalRecord> getCurrent(
            @PathVariable String entityType,
            @PathVariable String entityId) {
        BiTemporalRecord record = biTemporalService.getCurrent(entityType, entityId);
        return ApiResponse.success(record);
    }

    /**
     * Point-in-time query: find the record valid at the given business and system times.
     */
    @GetMapping("/{entityType}/{entityId}/asOf")
    public ApiResponse<BiTemporalRecord> getAsOf(
            @PathVariable String entityType,
            @PathVariable String entityId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime validTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime txTime) {
        if (txTime == null) {
            txTime = LocalDateTime.now();
        }
        BiTemporalRecord record = biTemporalService.getAsOf(entityType, entityId, validTime, txTime);
        return ApiResponse.success(record);
    }

    /**
     * Get the full version history of an entity.
     */
    @GetMapping("/{entityType}/{entityId}/history")
    public ApiResponse<List<BiTemporalRecord>> getHistory(
            @PathVariable String entityType,
            @PathVariable String entityId) {
        List<BiTemporalRecord> records = biTemporalService.getHistory(entityType, entityId);
        return ApiResponse.success(records);
    }

    /**
     * Create a new bi-temporal record.
     */
    @PostMapping("/{entityType}/{entityId}")
    public ApiResponse<BiTemporalRecord> put(
            @PathVariable String entityType,
            @PathVariable String entityId,
            @RequestBody BiTemporalRequest request) {
        Long userId = MetaContext.getCurrentUserId();
        LocalDateTime validTo = request.getValidTo() != null
                ? request.getValidTo()
                : BiTemporalRecord.INFINITY;

        BiTemporalRecord record = biTemporalService.put(
                entityType, entityId,
                request.getValidFrom(), validTo,
                request.getPayload(), userId);
        return ApiResponse.success(record);
    }

    /**
     * Correct an existing record: close the old transaction and insert a new corrected version.
     */
    @PutMapping("/{entityType}/{entityId}/correct")
    public ApiResponse<BiTemporalRecord> correct(
            @PathVariable String entityType,
            @PathVariable String entityId,
            @RequestBody BiTemporalRequest request) {
        Long userId = MetaContext.getCurrentUserId();
        LocalDateTime validTo = request.getValidTo() != null
                ? request.getValidTo()
                : BiTemporalRecord.INFINITY;

        BiTemporalRecord record = biTemporalService.correct(
                entityType, entityId,
                request.getValidFrom(), validTo,
                request.getPayload(), userId);
        return ApiResponse.success(record);
    }

    /**
     * Terminate an entity at the given business time.
     */
    @DeleteMapping("/{entityType}/{entityId}/terminate")
    public ApiResponse<Void> terminate(
            @PathVariable String entityType,
            @PathVariable String entityId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime validTime) {
        biTemporalService.terminate(entityType, entityId, validTime);
        return ApiResponse.success();
    }
}
