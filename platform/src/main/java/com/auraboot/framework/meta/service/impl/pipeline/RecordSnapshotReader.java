package com.auraboot.framework.meta.service.impl.pipeline;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.CommandExecutorUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared component for reading record snapshots and checking column existence.
 * Used by multiple pipeline phases (FieldMapPhase, ComputedFieldsPhase, HandlerPhase, AssertPhase).
 *
 * @author AuraBoot Team
 * @since 8.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecordSnapshotReader {

    private final MetaModelService metaModelService;
    private final DynamicDataMapper dynamicDataMapper;

    private static final int COLUMN_CACHE_MAX_SIZE = 1024;
    private final ConcurrentHashMap<String, Boolean> columnExistsCache = new ConcurrentHashMap<>();

    /**
     * Read a full record snapshot by tenant, model, and record ID.
     */
    public Map<String, Object> readRecordSnapshot(Long tenantId, String modelCode, String recordId) {
        try {
            String tableName = metaModelService.getTableName(modelCode);
            CommandExecutorUtils.validateSqlIdentifier(tableName, "snapshot tableName");
            var idEntry = CommandExecutorUtils.resolveRecordIdColumn(recordId);
            String sql = "SELECT * FROM " + tableName
                    + " WHERE tenant_id = #{params.tenantId} AND " + idEntry.getKey() + " = #{params.recordId}";
            Map<String, Object> params = Map.of("tenantId", tenantId, "recordId", idEntry.getValue());
            List<Map<String, Object>> result = dynamicDataMapper.selectByQuery(sql, params);
            if (result != null && !result.isEmpty()) {
                return result.get(0);
            }
        } catch (Exception e) {
            log.debug("Failed to read record snapshot: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Check if a table has a specific column using cached JDBC metadata.
     * Result is cached since table structure rarely changes at runtime.
     */
    public boolean hasColumn(String tableName, String columnName) {
        if (tableName == null || columnName == null) {
            return false;
        }
        String cacheKey = tableName + ":" + columnName;
        if (columnExistsCache.size() >= COLUMN_CACHE_MAX_SIZE) {
            var it = columnExistsCache.keySet().iterator();
            if (it.hasNext()) { it.next(); it.remove(); }
        }
        return columnExistsCache.computeIfAbsent(cacheKey, k -> {
            try {
                return dynamicDataMapper.checkColumnExists(tableName, columnName) > 0;
            } catch (Exception e) {
                log.debug("Failed to check column existence for {}.{}, assuming absent: {}",
                        tableName, columnName, e.getMessage());
                return false;
            }
        });
    }
}
