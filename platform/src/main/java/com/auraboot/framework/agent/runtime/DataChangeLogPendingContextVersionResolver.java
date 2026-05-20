package com.auraboot.framework.agent.runtime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Resolves pending context versions from the dynamic data audit log.
 */
@Service
public class DataChangeLogPendingContextVersionResolver implements PendingContextVersionResolver {

    private final JdbcTemplate jdbcTemplate;

    public DataChangeLogPendingContextVersionResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public PendingContextVersion resolve(PendingContextVersionRequest request) {
        if (request == null || !request.verifiable()) {
            return PendingContextVersion.unresolved(
                    request != null ? request.modelCode() : null,
                    request != null ? request.recordId() : null);
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, changed_at
                  FROM ab_data_change_log
                 WHERE tenant_id = ?
                   AND model_code = ?
                   AND record_id = ?
                 ORDER BY changed_at DESC, id DESC
                 LIMIT 1
                """, request.tenantId(), request.modelCode(), request.recordId());
        if (rows.isEmpty() || rows.get(0).get("id") == null) {
            return PendingContextVersion.unresolved(request.modelCode(), request.recordId());
        }
        String recordVersion = "change:" + rows.get(0).get("id");
        return new PendingContextVersion(
                request.modelCode(),
                request.recordId(),
                recordVersion,
                request.modelCode() + ":" + request.recordId() + ":" + recordVersion);
    }
}
