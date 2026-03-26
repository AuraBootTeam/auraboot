package com.auraboot.framework.meta.dto;

import lombok.Data;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * DTO for command audit log entries returned by the API.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Data
public class CommandAuditLogDTO {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private Long id;
    private String commandCode;
    private String commandPid;
    private Long userId;
    private String requestPayload;
    private String executionResult;
    private Boolean success;
    private String errorMessage;
    private Long executionTimeMs;
    private String phaseReached;
    /** JSON map of phase → duration_ms, e.g. {"load":5,"schema_validate":2,...} */
    private String phaseTimings;
    private Instant createdAt;

    public static CommandAuditLogDTO from(com.auraboot.framework.meta.entity.CommandAuditLog entity) {
        CommandAuditLogDTO dto = new CommandAuditLogDTO();
        dto.setId(entity.getId());
        dto.setCommandCode(entity.getCommandCode());
        dto.setCommandPid(entity.getCommandPid());
        dto.setUserId(entity.getUserId());
        dto.setRequestPayload(entity.getRequestPayload());
        dto.setExecutionResult(entity.getExecutionResult());
        dto.setSuccess(entity.getSuccess());
        dto.setErrorMessage(entity.getErrorMessage());
        dto.setExecutionTimeMs(entity.getExecutionTimeMs());
        dto.setPhaseReached(entity.getPhaseReached());
        dto.setPhaseTimings(normalizePhaseTimings(entity.getPhaseTimings()));
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }

    private static String normalizePhaseTimings(String phaseTimings) {
        if (phaseTimings == null || phaseTimings.isBlank()) {
            return phaseTimings;
        }
        try {
            Map<String, Object> timings = OBJECT_MAPPER.readValue(
                    phaseTimings, new TypeReference<Map<String, Object>>() {});
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : timings.entrySet()) {
                String key = entry.getKey() == null ? null : entry.getKey().toLowerCase(Locale.ROOT);
                normalized.put(key, entry.getValue());
            }
            return OBJECT_MAPPER.writeValueAsString(normalized);
        } catch (Exception e) {
            return phaseTimings;
        }
    }
}
