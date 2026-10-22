package com.auraboot.framework.agent.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Postgres-backed pending tool execution ledger.
 *
 * <p>It reuses {@code ab_idempotency_record} as the durable backstop so Redis
 * remains a fast session store while approved side effects get a DB-level
 * claim/replay boundary.
 */
@Slf4j
@Service
public class JdbcPendingToolExecutionLedger implements PendingToolExecutionLedger {

    private static final String COMMAND_CODE_PREFIX = "agent.pending_tool_execution:";
    private static final long EXPIRY_SECONDS = 24 * 60 * 60L;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcPendingToolExecutionLedger(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public PendingToolExecutionClaim claim(PendingToolSnapshot pendingTool) {
        Long tenantId = requireTenantId(pendingTool);
        String executionKey = PendingToolStore.executionKey(pendingTool);
        PendingToolExecutionRecord running = PendingToolExecutionRecord.running(executionKey);
        int inserted = jdbcTemplate.update("""
                INSERT INTO ab_idempotency_record
                    (tenant_id, client_request_id, request_hash, command_code, outcome, status, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, NOW() + (? || ' seconds')::interval, NOW())
                ON CONFLICT (tenant_id, client_request_id) DO NOTHING
                """,
                tenantId,
                executionKey,
                requestHash(pendingTool),
                commandCode(pendingTool),
                serialize(running),
                running.status().name(),
                String.valueOf(EXPIRY_SECONDS));
        if (inserted == 1) {
            return PendingToolExecutionClaim.acquired(executionKey);
        }
        return PendingToolExecutionClaim.replay(loadRecord(tenantId, executionKey));
    }

    @Override
    @Transactional
    public void complete(PendingToolSnapshot pendingTool, String executionKey, Map<String, Object> result) {
        storeTerminal(requireTenantId(pendingTool),
                executionKey,
                PendingToolExecutionRecord.succeeded(executionKey, result));
    }

    @Override
    @Transactional
    public void fail(PendingToolSnapshot pendingTool,
                     String executionKey,
                     Map<String, Object> result,
                     String errorMessage) {
        storeTerminal(requireTenantId(pendingTool),
                executionKey,
                PendingToolExecutionRecord.failed(executionKey, result, errorMessage));
    }

    private void storeTerminal(Long tenantId, String executionKey, PendingToolExecutionRecord record) {
        int updated = jdbcTemplate.update("""
                UPDATE ab_idempotency_record
                   SET outcome = ?::jsonb,
                       status = ?,
                       expires_at = GREATEST(expires_at, NOW() + (? || ' seconds')::interval)
                 WHERE tenant_id = ?
                   AND client_request_id = ?
                """,
                serialize(record),
                record.status().name(),
                String.valueOf(EXPIRY_SECONDS),
                tenantId,
                executionKey);
        if (updated == 0) {
            throw new IllegalStateException("Pending tool execution record missing for key " + executionKey);
        }
    }

    private PendingToolExecutionRecord loadRecord(Long tenantId, String executionKey) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT status, outcome::text AS outcome
                  FROM ab_idempotency_record
                 WHERE tenant_id = ?
                   AND client_request_id = ?
                   AND expires_at > NOW()
                """, tenantId, executionKey);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Pending tool execution claim exists but record is unavailable");
        }
        return parseRecord(executionKey, rows.get(0));
    }

    @SuppressWarnings("unchecked")
    private PendingToolExecutionRecord parseRecord(String executionKey, Map<String, Object> row) {
        Object rawOutcome = row.get("outcome");
        if (rawOutcome == null) {
            return new PendingToolExecutionRecord(
                    executionKey,
                    parseStatus(row.get("status")),
                    Map.of(),
                    null,
                    0L);
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(String.valueOf(rawOutcome), MAP_TYPE);
            Object result = payload.get("result");
            Map<String, Object> resultMap = result instanceof Map<?, ?> map
                    ? new LinkedHashMap<>((Map<String, Object>) map)
                    : Map.of();
            Object updatedAt = payload.get("updatedAt");
            return new PendingToolExecutionRecord(
                    stringOrDefault(payload.get("executionKey"), executionKey),
                    parseStatus(firstNonNull(payload.get("status"), row.get("status"))),
                    resultMap,
                    payload.get("errorMessage") != null ? String.valueOf(payload.get("errorMessage")) : null,
                    updatedAt instanceof Number number ? number.longValue() : 0L);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to parse pending tool execution record", e);
        }
    }

    private String serialize(PendingToolExecutionRecord record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize pending tool execution record", e);
        }
    }

    private Long requireTenantId(PendingToolSnapshot pendingTool) {
        if (pendingTool == null || pendingTool.getTenantId() == null) {
            throw new IllegalArgumentException("tenantId is required for pending tool execution ledger");
        }
        return pendingTool.getTenantId();
    }

    private String commandCode(PendingToolSnapshot pendingTool) {
        String toolName = pendingTool != null && pendingTool.getToolName() != null
                ? pendingTool.getToolName()
                : "tool";
        return COMMAND_CODE_PREFIX + toolName;
    }

    private String requestHash(PendingToolSnapshot pendingTool) {
        if (pendingTool != null && pendingTool.getArgsHash() != null && !pendingTool.getArgsHash().isBlank()) {
            return pendingTool.getArgsHash();
        }
        return sha256(PendingToolStore.executionKey(pendingTool));
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(String.valueOf(text).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute pending tool execution hash", e);
        }
    }

    private PendingToolExecutionStatus parseStatus(Object status) {
        if (status == null) {
            return PendingToolExecutionStatus.RUNNING;
        }
        try {
            return PendingToolExecutionStatus.valueOf(String.valueOf(status));
        } catch (IllegalArgumentException e) {
            log.warn("Unknown pending tool execution status {}; treating as RUNNING", status);
            return PendingToolExecutionStatus.RUNNING;
        }
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private String stringOrDefault(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }
}
