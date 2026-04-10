package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.TenantClock;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Handles AUTO_SET phase of the command execution pipeline.
 * Injects auto-generated values (codes, timestamps, user IDs) into payload.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommandAutoSetExecutor {

    private final DynamicDataMapper dynamicDataMapper;
    private final MetaModelService metaModelService;
    private final TenantClock tenantClock;

    @SuppressWarnings("unchecked")
    public void executeAutoSetPhase(Map<String, Object> execConfig, Map<String, Object> payload,
                              Long tenantId, Long userId, CommandDefinition command) {
        if (execConfig == null || !execConfig.containsKey("autoSetFields")) {
            return;
        }

        Map<String, Object> autoSetFields = (Map<String, Object>) execConfig.get("autoSetFields");
        if (autoSetFields == null || autoSetFields.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> entry : autoSetFields.entrySet()) {
            String fieldCode = entry.getKey();
            Map<String, Object> config = (Map<String, Object>) entry.getValue();
            String strategy = (String) config.get("strategy");

            Object value = switch (strategy) {
                case "auto_generate" -> generateAutoCode(command.getModelCode(), fieldCode, config);
                case "current_user" -> userId != null ? String.valueOf(userId) : null;
                case "current_user_pid" -> MetaContext.getCurrentUserPid();
                case "current_username" -> MetaContext.getCurrentUsername();
                case "current_date" -> tenantClock.businessDate(MetaContext.getCurrentTenantId());
                case "current_datetime" -> Instant.now();
                case "fixed_value" -> config.get("value");
                case "copy_field" -> payload.get(config.get("sourceField"));
                default -> {
                    log.warn("Unknown autoSet strategy '{}' for field '{}'", strategy, fieldCode);
                    yield null;
                }
            };

            if (value != null) {
                payload.put(fieldCode, value);
                log.debug("AUTO_SET: {} = {} (strategy={})", fieldCode, value, strategy);
            }
        }
    }

    /**
     * Generate auto-incrementing code like ISS-20260210-001.
     * Uses date prefix + sequence from existing records.
     */
    private String generateAutoCode(String modelCode, String fieldCode, Map<String, Object> config) {
        try {
            // Security: validate fieldCode and tableName to prevent SQL injection
            CommandExecutorUtils.validateSqlIdentifier(fieldCode, "autoCode fieldCode");
            Long tenantId = MetaContext.getCurrentTenantId();
            String tableName = metaModelService.getTableName(modelCode);
            CommandExecutorUtils.validateSqlIdentifier(tableName, "autoCode tableName");
            String datePrefix = tenantClock.businessDate(MetaContext.getCurrentTenantId())
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd"));

            // Determine code prefix: prefer pattern config, fallback to field-name derivation
            String codePrefix = extractPrefixFromPattern(config);
            if (codePrefix == null) {
                codePrefix = deriveCodePrefix(fieldCode);
            }
            String fullPrefix = codePrefix + "-" + datePrefix + "-";

            // Use MAX to get the highest existing sequence number
            String sql = "SELECT MAX(CAST(SUBSTRING(" + fieldCode + " FROM "
                    + (fullPrefix.length() + 1) + ") AS INTEGER)) as max_seq FROM " + tableName
                    + " WHERE tenant_id = #{params.tenantId} AND " + fieldCode + " LIKE #{params.prefix}";
            Map<String, Object> params = Map.of("tenantId", tenantId, "prefix", fullPrefix + "%");
            List<Map<String, Object>> result = dynamicDataMapper.selectByQuery(sql, params);
            long nextSeq = 1;
            if (result != null && !result.isEmpty() && result.get(0) != null) {
                Object maxSeq = result.get(0).get("max_seq");
                if (maxSeq instanceof Number) {
                    nextSeq = ((Number) maxSeq).longValue() + 1;
                }
            }

            return fullPrefix + String.format("%03d", nextSeq);
        } catch (Exception e) {
            log.warn("Failed to generate auto code for {}.{}, using UUID fallback: {}",
                    modelCode, fieldCode, e.getMessage());
            return UniqueIdGenerator.generate();
        }
    }

    private String extractPrefixFromPattern(Map<String, Object> config) {
        if (config == null) return null;
        Object patternObj = config.get("pattern");
        if (!(patternObj instanceof String pattern) || pattern.isEmpty()) return null;
        // Pattern format: "PREFIX-{yyyyMMdd}-{seq}" — extract text before first '{'
        int braceIndex = pattern.indexOf('{');
        if (braceIndex <= 0) return null;
        String prefix = pattern.substring(0, braceIndex);
        // Remove trailing separator (-, _, etc.)
        return prefix.replaceAll("[-_]+$", "");
    }

    private String deriveCodePrefix(String fieldCode) {
        if (fieldCode.contains("issue")) return "iss";
        if (fieldCode.contains("rect")) return "rct";
        if (fieldCode.contains("insp")) return "ins";
        if (fieldCode.contains("report")) return "rpt";
        if (fieldCode.contains("plan")) return "pln";
        if (fieldCode.contains("project")) return "prj";
        // Fallback: use first 3 chars of the part after last underscore
        String[] parts = fieldCode.split("_");
        String lastPart = parts[parts.length - 1];
        return lastPart.substring(0, Math.min(3, lastPart.length())).toUpperCase();
    }
}
