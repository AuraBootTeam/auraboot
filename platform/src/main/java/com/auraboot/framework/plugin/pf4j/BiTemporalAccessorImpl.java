package com.auraboot.framework.plugin.pf4j;

import com.auraboot.module.bitemporal.entity.BiTemporalRecord;
import com.auraboot.module.bitemporal.service.BiTemporalService;
import com.auraboot.framework.plugin.extension.BiTemporalAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Implementation of BiTemporalAccessor that delegates to BiTemporalService.
 * Provides plugin command handlers with controlled access to bi-temporal versioning.
 *
 * @author AuraBoot Team
 * @since 6.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class BiTemporalAccessorImpl implements BiTemporalAccessor {

    private final BiTemporalService biTemporalService;
    private final ObjectMapper objectMapper;

    @Override
    public Map<String, Object> put(String entityType, String entityId,
                                   LocalDateTime validFrom, LocalDateTime validTo,
                                   Map<String, Object> payload, Long userId) {
        log.debug("Plugin BiTemporalAccessor: put({}, {}, {} -> {})", entityType, entityId, validFrom, validTo);
        JsonNode payloadJson = objectMapper.valueToTree(payload);
        BiTemporalRecord record = biTemporalService.put(entityType, entityId, validFrom, validTo, payloadJson, userId);
        return toResultMap(record);
    }

    @Override
    public Map<String, Object> getAsOf(String entityType, String entityId,
                                       LocalDateTime validTime, LocalDateTime txTime) {
        log.debug("Plugin BiTemporalAccessor: getAsOf({}, {}, valid={}, tx={})", entityType, entityId, validTime, txTime);
        BiTemporalRecord record = biTemporalService.getAsOf(entityType, entityId, validTime, txTime);
        return record != null ? toPayloadMap(record) : null;
    }

    @Override
    public Map<String, Object> getCurrent(String entityType, String entityId) {
        log.debug("Plugin BiTemporalAccessor: getCurrent({}, {})", entityType, entityId);
        BiTemporalRecord record = biTemporalService.getCurrent(entityType, entityId);
        return record != null ? toPayloadMap(record) : null;
    }

    @Override
    public Map<String, Object> correct(String entityType, String entityId,
                                       LocalDateTime validFrom, LocalDateTime validTo,
                                       Map<String, Object> payload, Long userId) {
        log.debug("Plugin BiTemporalAccessor: correct({}, {}, {} -> {})", entityType, entityId, validFrom, validTo);
        JsonNode payloadJson = objectMapper.valueToTree(payload);
        BiTemporalRecord record = biTemporalService.correct(entityType, entityId, validFrom, validTo, payloadJson, userId);
        return toResultMap(record);
    }

    private Map<String, Object> toResultMap(BiTemporalRecord record) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", record.getId());
        result.put("entityType", record.getEntityType());
        result.put("entityId", record.getEntityId());
        result.put("versionNo", record.getVersionNo());
        result.put("validFrom", record.getValidFrom());
        result.put("validTo", record.getValidTo());
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toPayloadMap(BiTemporalRecord record) {
        if (record.getPayload() == null) {
            return new HashMap<>();
        }
        Map<String, Object> result = objectMapper.convertValue(record.getPayload(), Map.class);
        // Add metadata
        result.put("__versionNo", record.getVersionNo());
        result.put("__validFrom", record.getValidFrom());
        result.put("__validTo", record.getValidTo());
        result.put("__entityId", record.getEntityId());
        return result;
    }
}
