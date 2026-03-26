package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.OtDataLog;
import com.auraboot.framework.meta.entity.OtDevice;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.OtDataLogMapper;
import com.auraboot.framework.meta.mapper.OtDeviceMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * OT (Operational Technology) device integration service.
 *
 * <p>Provides the framework for connecting manufacturing equipment (AOI, ICT, FCT,
 * SMT pick-and-place, reflow ovens, etc.) to the ERP system. Devices push data
 * via REST webhook endpoints; the service parses, maps, and logs the data.
 *
 * <p>The service handles:
 * <ul>
 *   <li>Device registration and configuration management</li>
 *   <li>Device status tracking (ONLINE/OFFLINE/ERROR/MAINTENANCE)</li>
 *   <li>Heartbeat monitoring</li>
 *   <li>Data ingestion: parse raw device data → map to model fields → log</li>
 *   <li>Data log querying for diagnostics and auditing</li>
 * </ul>
 *
 * <p>Currently supports REST_API (webhook) as the primary protocol. MQTT, OPC-UA,
 * MODBUS, FILE_WATCH, and SECS/GEM adapters are extensible via the
 * connection_config JSONB and future adapter implementations.
 *
 * @since 5.3.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtDeviceService {

    private final OtDeviceMapper deviceMapper;
    private final OtDataLogMapper dataLogMapper;
    private final ObjectMapper objectMapper;

    // ==================== Device CRUD ====================

    /**
     * List all OT devices for the current tenant.
     */
    public List<OtDevice> listDevices() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return deviceMapper.findByTenantId(tenantId);
    }

    /**
     * Get an OT device by ID.
     */
    public OtDevice getDevice(Long id) {
        OtDevice device = deviceMapper.selectById(id);
        if (device == null) {
            throw new MetaServiceException("OT device not found: " + id);
        }
        return device;
    }

    /**
     * Get an OT device by code.
     */
    public OtDevice getDeviceByCode(String deviceCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        OtDevice device = deviceMapper.findByCode(tenantId, deviceCode);
        if (device == null) {
            throw new MetaServiceException("OT device not found: " + deviceCode);
        }
        return device;
    }

    /**
     * Register a new OT device.
     */
    @Transactional
    public OtDevice registerDevice(OtDevice device) {
        Long tenantId = MetaContext.getCurrentTenantId();
        device.setTenantId(tenantId);

        // Check for duplicate code
        OtDevice existing = deviceMapper.findByCode(tenantId, device.getDeviceCode());
        if (existing != null) {
            throw new MetaServiceException("OT device code already exists: " + device.getDeviceCode());
        }

        validateDeviceType(device.getDeviceType());
        validateDeviceProtocol(device.getProtocol());

        if (device.getStatus() == null) {
            device.setStatus("offline");
        }
        if (device.getEnabled() == null) {
            device.setEnabled(true);
        }
        if (device.getPollingIntervalMs() == null) {
            device.setPollingIntervalMs(5000);
        }

        deviceMapper.insert(device);
        log.info("Registered OT device: code={}, type={}, protocol={}",
                device.getDeviceCode(), device.getDeviceType(), device.getProtocol());
        return device;
    }

    /**
     * Update an OT device configuration.
     */
    @Transactional
    public OtDevice updateDevice(Long id, OtDevice updates) {
        OtDevice device = getDevice(id);
        Long tenantId = MetaContext.getCurrentTenantId();

        if (!tenantId.equals(device.getTenantId())) {
            throw new MetaServiceException("OT device not found: " + id);
        }

        if (updates.getDeviceName() != null) {
            device.setDeviceName(updates.getDeviceName());
        }
        if (updates.getDeviceType() != null) {
            validateDeviceType(updates.getDeviceType());
            device.setDeviceType(updates.getDeviceType());
        }
        if (updates.getProtocol() != null) {
            validateDeviceProtocol(updates.getProtocol());
            device.setProtocol(updates.getProtocol());
        }
        if (updates.getConnectionConfig() != null) {
            device.setConnectionConfig(updates.getConnectionConfig());
        }
        if (updates.getDataMapping() != null) {
            device.setDataMapping(updates.getDataMapping());
        }
        if (updates.getTargetModelCode() != null) {
            device.setTargetModelCode(updates.getTargetModelCode());
        }
        if (updates.getPollingIntervalMs() != null) {
            device.setPollingIntervalMs(updates.getPollingIntervalMs());
        }
        if (updates.getEnabled() != null) {
            device.setEnabled(updates.getEnabled());
        }

        deviceMapper.updateById(device);
        log.info("Updated OT device: id={}, code={}", id, device.getDeviceCode());
        return device;
    }

    /**
     * Delete an OT device (soft delete).
     */
    @Transactional
    public void deleteDevice(Long id) {
        OtDevice device = getDevice(id);
        Long tenantId = MetaContext.getCurrentTenantId();

        if (!tenantId.equals(device.getTenantId())) {
            throw new MetaServiceException("OT device not found: " + id);
        }

        deviceMapper.deleteById(id);
        log.info("Deleted OT device: id={}, code={}", id, device.getDeviceCode());
    }

    // ==================== Device Status ====================

    /**
     * Update device status.
     */
    @Transactional
    public void updateDeviceStatus(Long deviceId, String status) {
        Long tenantId = MetaContext.getCurrentTenantId();
        validateDeviceStatus(status);

        int affected = deviceMapper.updateStatus(deviceId, tenantId, status);
        if (affected == 0) {
            throw new MetaServiceException("OT device not found: " + deviceId);
        }

        log.info("Updated OT device status: id={}, status={}", deviceId, status);
    }

    /**
     * Process a heartbeat from a device.
     *
     * <p>Updates the device's last_heartbeat timestamp and sets status to ONLINE.
     *
     * @param deviceCode the device code
     * @return the updated device
     */
    @Transactional
    public OtDevice processHeartbeat(String deviceCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        OtDevice device = deviceMapper.findByCode(tenantId, deviceCode);
        if (device == null) {
            throw new MetaServiceException("OT device not found: " + deviceCode);
        }

        Instant now = Instant.now();
        deviceMapper.updateHeartbeat(device.getId(), tenantId, now);

        device.setLastHeartbeat(now);
        device.setStatus("online");
        return device;
    }

    /**
     * Get device status summary including last heartbeat.
     */
    public Map<String, Object> getDeviceStatus(String deviceCode) {
        OtDevice device = getDeviceByCode(deviceCode);

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("deviceCode", device.getDeviceCode());
        status.put("deviceName", device.getDeviceName());
        status.put("deviceType", device.getDeviceType());
        status.put("status", device.getStatus());
        status.put("lastHeartbeat", device.getLastHeartbeat());
        status.put("enabled", device.getEnabled());
        status.put("protocol", device.getProtocol());
        status.put("targetModelCode", device.getTargetModelCode());

        // Calculate time since last heartbeat
        if (device.getLastHeartbeat() != null) {
            long secondsSinceHeartbeat = Instant.now().getEpochSecond() - device.getLastHeartbeat().getEpochSecond();
            status.put("secondsSinceHeartbeat", secondsSinceHeartbeat);
            status.put("isStale", secondsSinceHeartbeat > (device.getPollingIntervalMs() / 1000L * 3));
        } else {
            status.put("secondsSinceHeartbeat", null);
            status.put("isStale", true);
        }

        return status;
    }

    // ==================== Data Processing ====================

    /**
     * Process incoming device data.
     *
     * <p>Workflow:
     * <ol>
     *   <li>Validate device exists and is enabled</li>
     *   <li>Parse raw data based on device's data_mapping</li>
     *   <li>Map parsed fields to target model fields</li>
     *   <li>Create data log entry</li>
     *   <li>In production, would create/update record via Command engine</li>
     * </ol>
     *
     * @param deviceCode device code
     * @param rawData    raw data from the device (JSON object)
     * @return the data log entry
     */
    @Transactional
    public OtDataLog processDeviceData(String deviceCode, Map<String, Object> rawData) {
        Long tenantId = MetaContext.getCurrentTenantId();
        OtDevice device = deviceMapper.findByCode(tenantId, deviceCode);
        if (device == null) {
            throw new MetaServiceException("OT device not found: " + deviceCode);
        }

        if (!device.getEnabled()) {
            throw new MetaServiceException("OT device is disabled: " + deviceCode);
        }

        long startTime = System.currentTimeMillis();

        // Create data log entry
        OtDataLog dataLog = new OtDataLog();
        dataLog.setTenantId(tenantId);
        dataLog.setDeviceId(device.getId());
        dataLog.setTimestamp(Instant.now());
        dataLog.setStatus("received");

        try {
            dataLog.setRawData(objectMapper.writeValueAsString(rawData));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize raw data for device {}", deviceCode, e);
            dataLog.setRawData("{}");
        }

        try {
            // Parse and map the data
            Map<String, Object> parsedData = parseDeviceData(device, rawData);
            dataLog.setParsedData(objectMapper.writeValueAsString(parsedData));

            // Map to model fields
            Map<String, Object> modelData = mapDeviceDataToModel(device, parsedData);

            // In production, would execute a CREATE command on the target model:
            // commandEngine.execute(device.getTargetModelCode(), "create", modelData);
            log.info("OT device data processed: device={}, fields={}, targetModel={}",
                    deviceCode, modelData.size(), device.getTargetModelCode());

            dataLog.setStatus("processed");
        } catch (Exception e) {
            log.error("Failed to process device data: device={}", deviceCode, e);
            dataLog.setStatus(StatusConstants.FAILED);
            dataLog.setErrorMessage(e.getMessage());
        }

        long processingTime = System.currentTimeMillis() - startTime;
        dataLog.setProcessingTimeMs((int) processingTime);

        dataLogMapper.insert(dataLog);

        // Update device heartbeat on successful data reception
        deviceMapper.updateHeartbeat(device.getId(), tenantId, Instant.now());

        return dataLog;
    }

    /**
     * Parse raw device data using the device's data_mapping configuration.
     *
     * <p>The data_mapping JSONB defines how to extract values from raw device data:
     * <pre>
     * {
     *   "extractions": {
     *     "temperature": "$.sensors.temp",
     *     "pressure": "$.sensors.pressure",
     *     "result": "$.inspection.pass"
     *   }
     * }
     * </pre>
     *
     * <p>For simple flat structures, data is passed through directly.
     */
    private Map<String, Object> parseDeviceData(OtDevice device, Map<String, Object> rawData) {
        if (device.getDataMapping() == null) {
            // No mapping defined, pass through raw data
            return rawData;
        }

        try {
            JsonNode mappingNode = objectMapper.readTree(device.getDataMapping());
            JsonNode extractions = mappingNode.get("extractions");

            if (extractions == null) {
                return rawData;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            JsonNode rawNode = objectMapper.valueToTree(rawData);

            Iterator<Map.Entry<String, JsonNode>> fields = extractions.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String outputField = entry.getKey();
                String jsonPath = entry.getValue().asText();

                // Simple dot-notation path resolution (e.g. "sensors.temp")
                Object value = resolveJsonPath(rawNode, jsonPath);
                if (value != null) {
                    result.put(outputField, value);
                }
            }

            return result;
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse data mapping for device {}", device.getDeviceCode(), e);
            return rawData;
        }
    }

    /**
     * Map parsed device data to target model fields.
     *
     * <p>Uses the "fieldMapping" section of the device's data_mapping:
     * <pre>
     * {
     *   "fieldMapping": {
     *     "temperature": "pe_temperature",
     *     "pressure": "pe_pressure",
     *     "result": "qc_test_result"
     *   }
     * }
     * </pre>
     */
    private Map<String, Object> mapDeviceDataToModel(OtDevice device, Map<String, Object> parsedData) {
        if (device.getDataMapping() == null) {
            return parsedData;
        }

        try {
            JsonNode mappingNode = objectMapper.readTree(device.getDataMapping());
            JsonNode fieldMapping = mappingNode.get("fieldMapping");

            if (fieldMapping == null) {
                return parsedData;
            }

            Map<String, Object> modelData = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = fieldMapping.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String deviceField = entry.getKey();
                String modelField = entry.getValue().asText();

                if (parsedData.containsKey(deviceField)) {
                    modelData.put(modelField, parsedData.get(deviceField));
                }
            }

            return modelData;
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse field mapping for device {}", device.getDeviceCode(), e);
            return parsedData;
        }
    }

    /**
     * Simple JSON path resolution using dot notation.
     * Supports paths like "sensors.temp" or "$.sensors.temp".
     */
    private Object resolveJsonPath(JsonNode root, String path) {
        // Strip leading "$." if present
        if (path.startsWith("$.")) {
            path = path.substring(2);
        }

        String[] parts = path.split("\\.");
        JsonNode current = root;

        for (String part : parts) {
            if (current == null || current.isMissingNode()) {
                return null;
            }
            current = current.get(part);
        }

        if (current == null || current.isMissingNode() || current.isNull()) {
            return null;
        }

        if (current.isTextual()) return current.asText();
        if (current.isNumber()) return current.numberValue();
        if (current.isBoolean()) return current.asBoolean();
        return current.toString();
    }

    // ==================== Data Log Queries ====================

    /**
     * Get data log entries for a device within a date range.
     */
    public List<OtDataLog> getDataLog(Long deviceId, Instant start, Instant end) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return dataLogMapper.findByDeviceAndDateRange(tenantId, deviceId, start, end);
    }

    /**
     * Get recent data log entries for a device.
     */
    public List<OtDataLog> getRecentDataLog(Long deviceId, int limit) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return dataLogMapper.findRecentByDevice(tenantId, deviceId, limit);
    }

    /**
     * Get data log entries by status (for monitoring failed/pending entries).
     */
    public List<OtDataLog> getDataLogByStatus(String status, int limit) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return dataLogMapper.findByStatus(tenantId, status, limit);
    }

    /**
     * Get device data statistics.
     */
    public Map<String, Object> getDeviceStats(String deviceCode) {
        OtDevice device = getDeviceByCode(deviceCode);
        Long tenantId = MetaContext.getCurrentTenantId();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("deviceCode", device.getDeviceCode());
        stats.put("deviceName", device.getDeviceName());
        stats.put("status", device.getStatus());

        long processed = dataLogMapper.countByDeviceAndStatus(tenantId, device.getId(), "processed");
        long failed = dataLogMapper.countByDeviceAndStatus(tenantId, device.getId(), "failed");
        long received = dataLogMapper.countByDeviceAndStatus(tenantId, device.getId(), "received");

        stats.put("processedCount", processed);
        stats.put("failedCount", failed);
        stats.put("pendingCount", received);
        stats.put("totalCount", processed + failed + received);

        return stats;
    }

    // ==================== Validation Helpers ====================

    private void validateDeviceType(String deviceType) {
        Set<String> validTypes = Set.of(
                "aoi", "ict", "fct", "smt_pp", "reflow",
                "wave_solder", "spi", "xray", "laser_mark"
        );
        if (!validTypes.contains(deviceType)) {
            throw new MetaServiceException("Invalid device type: " + deviceType + ". Must be one of: " + validTypes);
        }
    }

    private void validateDeviceProtocol(String protocol) {
        Set<String> validProtocols = Set.of(
                "opcua", "mqtt", "modbus", "rest_api", "file_watch", "secs_gem"
        );
        if (!validProtocols.contains(protocol)) {
            throw new MetaServiceException("Invalid device protocol: " + protocol + ". Must be one of: " + validProtocols);
        }
    }

    private void validateDeviceStatus(String status) {
        Set<String> validStatuses = Set.of("online", "offline", "error", "maintenance");
        if (!validStatuses.contains(status)) {
            throw new MetaServiceException("Invalid device status: " + status + ". Must be one of: " + validStatuses);
        }
    }
}
