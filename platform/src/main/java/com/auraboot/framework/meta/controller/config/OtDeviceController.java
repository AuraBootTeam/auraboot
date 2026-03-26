package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.entity.OtDataLog;
import com.auraboot.framework.meta.entity.OtDevice;
import com.auraboot.framework.meta.service.impl.OtDeviceService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * REST controller for OT (Operational Technology) device integration.
 *
 * <p>Provides endpoints for managing manufacturing equipment devices,
 * receiving device data via webhooks, heartbeat monitoring, and querying
 * data ingestion logs.
 *
 * @since 5.3.0
 */
@RestController
@RequestMapping("/api/ot")
@RequiredArgsConstructor
public class OtDeviceController {

    private final OtDeviceService otDeviceService;

    // ==================== Device Management ====================

    /**
     * List all OT devices.
     * GET /api/ot/devices
     */
    @GetMapping("/devices")
    @RequirePermission(MetaPermission.OT_DEVICE_READ)
    public ApiResponse<List<OtDevice>> listDevices() {
        return ApiResponse.success(otDeviceService.listDevices());
    }

    /**
     * Get an OT device by ID.
     * GET /api/ot/devices/{id}
     */
    @GetMapping("/devices/{id}")
    @RequirePermission(MetaPermission.OT_DEVICE_READ)
    public ApiResponse<OtDevice> getDevice(@PathVariable Long id) {
        return ApiResponse.success(otDeviceService.getDevice(id));
    }

    /**
     * Register a new OT device.
     * POST /api/ot/devices
     */
    @PostMapping("/devices")
    @RequirePermission(MetaPermission.OT_DEVICE_MANAGE)
    public ApiResponse<OtDevice> registerDevice(@RequestBody OtDevice device) {
        return ApiResponse.success(otDeviceService.registerDevice(device));
    }

    /**
     * Update an OT device configuration.
     * PUT /api/ot/devices/{id}
     */
    @PutMapping("/devices/{id}")
    @RequirePermission(MetaPermission.OT_DEVICE_MANAGE)
    public ApiResponse<OtDevice> updateDevice(@PathVariable Long id, @RequestBody OtDevice device) {
        return ApiResponse.success(otDeviceService.updateDevice(id, device));
    }

    /**
     * Delete an OT device (soft delete).
     * DELETE /api/ot/devices/{id}
     */
    @DeleteMapping("/devices/{id}")
    @RequirePermission(MetaPermission.OT_DEVICE_MANAGE)
    public ApiResponse<Map<String, Object>> deleteDevice(@PathVariable Long id) {
        otDeviceService.deleteDevice(id);
        return ApiResponse.success(Map.of("success", true, "id", id));
    }

    // ==================== Device Status ====================

    /**
     * Get device status including heartbeat info.
     * GET /api/ot/devices/{code}/status
     */
    @GetMapping("/devices/{code}/status")
    @RequirePermission(MetaPermission.OT_DEVICE_READ)
    public ApiResponse<Map<String, Object>> getDeviceStatus(@PathVariable String code) {
        return ApiResponse.success(otDeviceService.getDeviceStatus(code));
    }

    /**
     * Update device status.
     * PUT /api/ot/devices/{id}/status
     * Body: { "status": "maintenance" }
     */
    @PutMapping("/devices/{id}/status")
    @RequirePermission(MetaPermission.OT_DEVICE_MANAGE)
    public ApiResponse<Map<String, Object>> updateDeviceStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String status = body.get("status");
        otDeviceService.updateDeviceStatus(id, status);
        return ApiResponse.success(Map.of("success", true, "id", id, "status", status));
    }

    // ==================== Device Data Ingestion ====================

    /**
     * Receive data from a device (webhook endpoint).
     * POST /api/ot/devices/{code}/data
     *
     * <p>Called by devices or device gateways to push data into the system.
     * The raw JSON payload is parsed, mapped to model fields, and logged.
     */
    @PostMapping("/devices/{code}/data")
    @RequirePermission(MetaPermission.OT_DEVICE_DATA)
    public ApiResponse<OtDataLog> receiveDeviceData(
            @PathVariable String code,
            @RequestBody Map<String, Object> data) {
        return ApiResponse.success(otDeviceService.processDeviceData(code, data));
    }

    /**
     * Heartbeat endpoint for device connectivity monitoring.
     * POST /api/ot/devices/{code}/heartbeat
     *
     * <p>Devices should call this periodically to signal they are alive.
     * Updates the device's last_heartbeat and sets status to ONLINE.
     */
    @PostMapping("/devices/{code}/heartbeat")
    @RequirePermission(MetaPermission.OT_DEVICE_DATA)
    public ApiResponse<OtDevice> heartbeat(@PathVariable String code) {
        return ApiResponse.success(otDeviceService.processHeartbeat(code));
    }

    // ==================== Data Log ====================

    /**
     * Get data log entries for a device.
     * GET /api/ot/data-log?deviceId=1&start=2026-01-01T00:00:00Z&end=2026-12-31T23:59:59Z
     * GET /api/ot/data-log?deviceId=1&limit=100
     */
    @GetMapping("/data-log")
    @RequirePermission(MetaPermission.OT_DEVICE_READ)
    public ApiResponse<List<OtDataLog>> getDataLog(
            @RequestParam Long deviceId,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(defaultValue = "100") int limit) {
        if (start != null && end != null) {
            Instant startInstant = Instant.parse(start);
            Instant endInstant = Instant.parse(end);
            return ApiResponse.success(otDeviceService.getDataLog(deviceId, startInstant, endInstant));
        }
        return ApiResponse.success(otDeviceService.getRecentDataLog(deviceId, limit));
    }

    /**
     * Get data log entries by status (for monitoring).
     * GET /api/ot/data-log/by-status?status=FAILED&limit=50
     */
    @GetMapping("/data-log/by-status")
    @RequirePermission(MetaPermission.OT_DEVICE_READ)
    public ApiResponse<List<OtDataLog>> getDataLogByStatus(
            @RequestParam String status,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(otDeviceService.getDataLogByStatus(status, limit));
    }

    /**
     * Get device data statistics.
     * GET /api/ot/devices/{code}/stats
     */
    @GetMapping("/devices/{code}/stats")
    @RequirePermission(MetaPermission.OT_DEVICE_READ)
    public ApiResponse<Map<String, Object>> getDeviceStats(@PathVariable String code) {
        return ApiResponse.success(otDeviceService.getDeviceStats(code));
    }
}
