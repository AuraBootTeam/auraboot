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
 * Postgres-backed direct tool execution ledger.
 */
@Slf4j
@Service
public class JdbcDurableToolExecutionLedger implements DurableToolExecutionLedger {

    private static final String COMMAND_CODE_PREFIX = "agent.tool_execution:";
    private static final long EXPIRY_SECONDS = 24 * 60 * 60L;
    private static final long[] BACKOFFS_MILLIS = {60_000L, 300_000L, 1_800_000L};
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcDurableToolExecutionLedger(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public DurableToolExecutionClaim claim(DurableToolExecutionRequest request) {
        if (request == null || request.tenantId() == null) {
            throw new IllegalArgumentException("tenantId is required for durable tool execution");
        }
        String executionKey = request.executionKey();
        DurableToolExecutionRecord running = DurableToolExecutionRecord.running(executionKey, request);
        int inserted = jdbcTemplate.update("""
                INSERT INTO ab_idempotency_record
                    (tenant_id, client_request_id, request_hash, command_code, outcome, status, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, NOW() + (? || ' seconds')::interval, NOW())
                ON CONFLICT (tenant_id, client_request_id) DO NOTHING
                """,
                request.tenantId(),
                executionKey,
                requestHash(request),
                commandCode(request),
                serialize(running),
                running.status().name(),
                String.valueOf(EXPIRY_SECONDS));
        if (inserted == 1) {
            return DurableToolExecutionClaim.acquired(executionKey);
        }
        return DurableToolExecutionClaim.replay(loadRecord(request.tenantId(), executionKey));
    }

    @Override
    @Transactional
    public void complete(DurableToolExecutionRequest request, String executionKey, String rawResult) {
        DurableToolExecutionRecord current = loadRecordOrFallback(request, executionKey);
        storeTerminal(request, executionKey, DurableToolExecutionRecord.succeeded(
                executionKey,
                rawResult,
                parseResult(rawResult),
                current));
    }

    @Override
    @Transactional
    public void fail(DurableToolExecutionRequest request,
                     String executionKey,
                     String rawResult,
                     String errorMessage) {
        DurableToolExecutionRecord current = loadRecordOrFallback(request, executionKey);
        storeTerminal(request, executionKey, failedRecord(current, rawResult, errorMessage));
    }

    @Override
    public List<DurableToolExecutionRecord> findRecoverable(int limit) {
        long now = System.currentTimeMillis();
        int effectiveLimit = limit <= 0 ? 50 : Math.min(limit, 500);
        return jdbcTemplate.queryForList("""
                SELECT tenant_id, client_request_id, status, outcome::text AS outcome
                  FROM ab_idempotency_record
                 WHERE command_code LIKE ?
                   AND status = 'FAILED'
                   AND expires_at > NOW()
                   AND COALESCE((outcome->>'retryable')::boolean, false) = true
                   AND COALESCE((outcome->>'nextRetryAt')::bigint, 0) <= ?
                 ORDER BY created_at ASC
                 LIMIT ?
                """, COMMAND_CODE_PREFIX + "%", now, effectiveLimit)
                .stream()
                .map(row -> parseRecord(String.valueOf(row.get("client_request_id")), row))
                .toList();
    }

    @Override
    @Transactional
    public boolean claimRetry(DurableToolExecutionRecord record) {
        if (record == null || record.request() == null || record.request().tenantId() == null) {
            return false;
        }
        DurableToolExecutionRecord retrying = record.retryRunning();
        int updated = jdbcTemplate.update("""
                UPDATE ab_idempotency_record
                   SET outcome = ?::jsonb,
                       status = 'RUNNING',
                       expires_at = GREATEST(expires_at, NOW() + (? || ' seconds')::interval)
                 WHERE tenant_id = ?
                   AND client_request_id = ?
                   AND status = 'FAILED'
                """,
                serialize(retrying),
                String.valueOf(EXPIRY_SECONDS),
                record.request().tenantId(),
                record.executionKey());
        return updated == 1;
    }

    @Override
    @Transactional
    public void markCompensationRequired(DurableToolExecutionRecord record, String reason) {
        if (record == null || record.request() == null || record.request().tenantId() == null) {
            throw new IllegalArgumentException("tenantId is required for durable tool execution compensation");
        }
        storeTerminal(record.request(), record.executionKey(), record.compensationRequired(reason));
    }

    @Override
    public List<DurableToolExecutionRecord> findCompensationRequired(int limit) {
        int effectiveLimit = limit <= 0 ? 50 : Math.min(limit, 500);
        return jdbcTemplate.queryForList("""
                SELECT tenant_id, client_request_id, status, outcome::text AS outcome
                  FROM ab_idempotency_record
                 WHERE command_code LIKE ?
                   AND status = 'COMPENSATION_REQUIRED'
                   AND expires_at > NOW()
                 ORDER BY created_at ASC
                 LIMIT ?
                """, COMMAND_CODE_PREFIX + "%", effectiveLimit)
                .stream()
                .map(row -> parseRecord(String.valueOf(row.get("client_request_id")), row))
                .toList();
    }

    @Override
    @Transactional
    public void markCompensated(DurableToolExecutionRecord record, String rawResult) {
        if (record == null || record.request() == null || record.request().tenantId() == null) {
            throw new IllegalArgumentException("tenantId is required for durable tool execution compensation");
        }
        storeTerminal(record.request(), record.executionKey(),
                record.compensated(rawResult, parseResult(rawResult)));
    }

    private void storeTerminal(DurableToolExecutionRequest request,
                               String executionKey,
                               DurableToolExecutionRecord record) {
        if (request == null || request.tenantId() == null) {
            throw new IllegalArgumentException("tenantId is required for durable tool execution");
        }
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
                request.tenantId(),
                executionKey);
        if (updated == 0) {
            throw new IllegalStateException("Durable tool execution record missing for key " + executionKey);
        }
    }

    private DurableToolExecutionRecord loadRecordOrFallback(DurableToolExecutionRequest request, String executionKey) {
        if (request == null || request.tenantId() == null) {
            throw new IllegalArgumentException("tenantId is required for durable tool execution");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT status, outcome::text AS outcome
                  FROM ab_idempotency_record
                 WHERE tenant_id = ?
                   AND client_request_id = ?
                   AND expires_at > NOW()
                """, request.tenantId(), executionKey);
        if (rows.isEmpty()) {
            return DurableToolExecutionRecord.running(executionKey, request);
        }
        return parseRecord(executionKey, rows.get(0));
    }

    private DurableToolExecutionRecord failedRecord(DurableToolExecutionRecord current,
                                                    String rawResult,
                                                    String errorMessage) {
        Map<String, Object> parsed = parseResult(rawResult);
        if (current == null || !current.retryable()) {
            DurableToolExecutionRecord base = current != null
                    ? current
                    : DurableToolExecutionRecord.failed(null, rawResult, parsed, errorMessage);
            return base.retryFailed(rawResult, parsed, errorMessage, 0L)
                    .compensationRequired("not retryable: " + safeReason(errorMessage));
        }
        if (current.attemptCount() >= current.maxAttempts()) {
            return current.retryFailed(rawResult, parsed, errorMessage, 0L)
                    .compensationRequired("retry attempts exhausted: " + safeReason(errorMessage));
        }
        return current.retryFailed(rawResult, parsed, errorMessage, nextRetryAt(current));
    }

    private DurableToolExecutionRecord loadRecord(Long tenantId, String executionKey) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT status, outcome::text AS outcome
                  FROM ab_idempotency_record
                 WHERE tenant_id = ?
                   AND client_request_id = ?
                   AND expires_at > NOW()
                """, tenantId, executionKey);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Durable tool execution claim exists but record is unavailable");
        }
        return parseRecord(executionKey, rows.get(0));
    }

    @SuppressWarnings("unchecked")
    private DurableToolExecutionRecord parseRecord(String executionKey, Map<String, Object> row) {
        Object rawOutcome = row.get("outcome");
        if (rawOutcome == null) {
            return new DurableToolExecutionRecord(
                    executionKey,
                    parseStatus(row.get("status")),
                    null,
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
            DurableToolExecutionRequest request = parseRequest(payload.get("request"));
            return new DurableToolExecutionRecord(
                    stringOrDefault(payload.get("executionKey"), executionKey),
                    parseStatus(firstNonNull(payload.get("status"), row.get("status"))),
                    payload.get("rawResult") != null ? String.valueOf(payload.get("rawResult")) : null,
                    resultMap,
                    payload.get("errorMessage") != null ? String.valueOf(payload.get("errorMessage")) : null,
                    updatedAt instanceof Number number ? number.longValue() : 0L,
                    request,
                    intValue(payload.get("attemptCount"), 0),
                    intValue(payload.get("maxAttempts"), DurableToolExecutionRecord.DEFAULT_MAX_ATTEMPTS),
                    longValue(payload.get("nextRetryAt"), 0L),
                    booleanValue(payload.get("retryable")),
                    payload.get("compensationReason") != null
                            ? String.valueOf(payload.get("compensationReason"))
                            : null);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to parse durable tool execution record", e);
        }
    }

    private DurableToolExecutionRequest parseRequest(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.convertValue(value, DurableToolExecutionRequest.class);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String serialize(DurableToolExecutionRecord record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize durable tool execution record", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseResult(String rawResult) {
        if (rawResult == null || rawResult.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = objectMapper.readValue(rawResult, Object.class);
            return parsed instanceof Map<?, ?> map
                    ? new LinkedHashMap<>((Map<String, Object>) map)
                    : Map.of("value", parsed);
        } catch (Exception e) {
            return Map.of("rawResult", rawResult);
        }
    }

    private String commandCode(DurableToolExecutionRequest request) {
        String tool = request.toolRef() != null && !request.toolRef().isBlank()
                ? request.toolRef()
                : request.toolName();
        return COMMAND_CODE_PREFIX + (tool != null && !tool.isBlank() ? tool : "tool");
    }

    private String requestHash(DurableToolExecutionRequest request) {
        if (request.argsHash() != null && !request.argsHash().isBlank()) {
            return request.argsHash();
        }
        return sha256(request.executionKey());
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
            throw new IllegalStateException("Unable to hash durable tool execution request", e);
        }
    }

    private DurableToolExecutionStatus parseStatus(Object status) {
        if (status == null) {
            return DurableToolExecutionStatus.RUNNING;
        }
        try {
            return DurableToolExecutionStatus.valueOf(String.valueOf(status));
        } catch (IllegalArgumentException e) {
            return DurableToolExecutionStatus.RUNNING;
        }
    }

    private long nextRetryAt(DurableToolExecutionRecord record) {
        int index = Math.max(0, record.attemptCount() - 1);
        long backoff = BACKOFFS_MILLIS[Math.min(index, BACKOFFS_MILLIS.length - 1)];
        return System.currentTimeMillis() + backoff;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private String safeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown";
        }
        return reason.length() > 500 ? reason.substring(0, 500) : reason;
    }

    private Object firstNonNull(Object left, Object right) {
        return left != null ? left : right;
    }

    private String stringOrDefault(Object value, String fallback) {
        return value != null ? String.valueOf(value) : fallback;
    }
}
