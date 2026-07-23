package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.config.AgentProperties;
import com.auraboot.framework.agent.dto.BatchRequest;
import com.auraboot.framework.agent.dto.BatchResult;
import com.auraboot.framework.agent.dto.BatchStatus;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.SsrfValidator;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * P0-4 — Anthropic Messages Batch API client.
 *
 * <p>Anthropic's batch API accepts up to 10,000 requests per batch and bills
 * input + output at <strong>50% of the synchronous rate</strong>, with a
 * 24-hour SLA on results. It is a strict superset of the synchronous
 * {@link AnthropicLlmProvider#chat} body — every per-request entry carries the
 * same {@link com.auraboot.framework.agent.dto.AnthropicRequest} shape (model,
 * max_tokens, system, messages, tools, thinking, ...) plus a caller-supplied
 * {@code custom_id} for result reconciliation.
 *
 * <p>This service is intentionally thin — three endpoints, no business logic:
 * <ol>
 *   <li>{@link #submitBatch(List)} — POST {@code /v1/messages/batches}, returns
 *       the {@code msgbatch_...} batch id.</li>
 *   <li>{@link #getBatch(String)} — GET {@code /v1/messages/batches/{id}},
 *       returns processing status + per-status counts.</li>
 *   <li>{@link #getResults(String)} — GET {@code /v1/messages/batches/{id}/results},
 *       parses the JSONL stream into a list of {@link BatchResult}.</li>
 * </ol>
 *
 * <p>API key + base URL come from {@link AgentProperties.Anthropic} so the
 * batch path shares the same {@code agent.anthropic.*} application.yml block
 * as the synchronous provider. The WebClient bean is the same {@code aiWebClient}
 * to inherit max-in-memory codec sizing.
 *
 * <p>Red-line compliance: no fallback / ensure / retry. Network failures and
 * 4xx responses propagate up as {@link RuntimeException}; the caller
 * (BatchJobPoller) will let the next tick retry rather than this service
 * silently masking errors.
 */
@Slf4j
@Service
public class AnthropicBatchService {

    /**
     * Anthropic's required API version header. Pinned to the date that
     * introduced batches (2024-09-24) — same string the synchronous provider
     * uses ({@code 2023-06-01}) is also accepted, but pinning to the batch
     * cut-off makes the dependency explicit.
     */
    static final String ANTHROPIC_VERSION = "2023-06-01";

    /**
     * Anthropic-side beta header gate for batches. Required as of 2024-09-24
     * cut-off; documented as part of the Messages Batch API public preview.
     * If a future release promotes this to GA, the constant becomes a no-op
     * (the header is silently accepted).
     */
    static final String ANTHROPIC_BETA = "message-batches-2024-09-24";

    /** Default job status when submitBatch persists the row. */
    static final String STATUS_SUBMITTED = "submitted";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final AgentProperties agentProperties;
    private final JdbcTemplate jdbc;

    public AnthropicBatchService(@Qualifier("aiWebClient") WebClient webClient,
                                 ObjectMapper objectMapper,
                                 AgentProperties agentProperties,
                                 JdbcTemplate jdbc) {
        // ObjectMapper / AgentProperties are required even for parser-only
        // unit tests (parseJsonl uses ObjectMapper; submit uses
        // AgentProperties for the API key). WebClient and JdbcTemplate are
        // nullable so callers can build a parser-only instance for
        // {@link #parseJsonl} and DTO-validation unit tests; we re-check
        // these in the methods that actually need them.
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.agentProperties = Objects.requireNonNull(agentProperties, "agentProperties");
        this.webClient = webClient;
        this.jdbc = jdbc;
    }

    /**
     * Submit a batch of message requests, tracked via a {@code purpose} label
     * so callers can later list / poll their own jobs without colliding with
     * other subsystems.
     *
     * <p>Wire shape: {@code POST {baseUrl}/v1/messages/batches} with body
     * {@code { "requests": [ { "custom_id": "...", "params": { ...AnthropicRequest... } }, ... ] }}.
     *
     * <p>Side effect: writes a row into {@code ab_agent_batch_job} with
     * {@code status='submitted'} and the returned batch id, so the
     * {@link com.auraboot.framework.agent.scheduler.BatchJobPoller} cron can
     * later pick it up. The row's {@code tenant_id} comes from
     * {@link MetaContext#get()} — callers must run inside a tenant context.
     *
     * @param requests the batch entries; must be non-empty.
     * @param purpose  classifier used to filter rows in the poller and in
     *                 dashboards (e.g. {@code memory_promotion_scoring}).
     * @return the {@code msgbatch_...} batch id as returned by Anthropic.
     * @throws IllegalArgumentException if {@code requests} is null/empty or
     *                                  {@code purpose} is null/blank.
     * @throws RuntimeException         on JSON serialisation failure or HTTP error.
     */
    public String submitBatch(List<BatchRequest> requests, String purpose) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("submitBatch requires at least one request");
        }
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("submitBatch requires a purpose label");
        }
        AgentProperties.Anthropic cfg = agentProperties.getAnthropic();
        requireApiKey(cfg);

        // Build wire envelope: { "requests": [ { "custom_id": ..., "params": {...} } ] }.
        // Use snake_case keys explicitly to avoid relying on a global Jackson
        // naming-strategy that other DTOs in this codebase rely on remaining
        // camelCase.
        List<Map<String, Object>> wire = new ArrayList<>(requests.size());
        for (BatchRequest req : requests) {
            if (req.getCustomId() == null || req.getCustomId().isBlank()) {
                throw new IllegalArgumentException("BatchRequest.customId is required");
            }
            if (req.getParams() == null) {
                throw new IllegalArgumentException("BatchRequest.params is required");
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("custom_id", req.getCustomId());
            entry.put("params", req.getParams());
            wire.add(entry);
        }
        Map<String, Object> body = Map.of("requests", wire);

        String requestJson;
        try {
            requestJson = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialise batch request body", e);
        }

        // SSRF guard (SEC-20260723-05): baseUrl is config-supplied; reject private/
        // loopback/link-local targets and disallowed schemes before the outbound call.
        SsrfValidator.validate(cfg.getBaseUrl() + "/v1/messages/batches");
        String responseBody = webClient.post()
                .uri(cfg.getBaseUrl() + "/v1/messages/batches")
                .header("x-api-key", cfg.getApiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("anthropic-beta", ANTHROPIC_BETA)
                .header("content-type", "application/json")
                .bodyValue(requestJson)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        String batchId;
        try {
            // Response envelope carries id + processing_status + counts; we
            // only need the id at submit time. Parsing as a Map avoids
            // imposing a DTO shape just for one field.
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = objectMapper.readValue(responseBody, Map.class);
            Object id = resp.get("id");
            if (id == null) {
                throw new RuntimeException("Anthropic batch response missing 'id': " + responseBody);
            }
            batchId = id.toString();
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse batch submit response: " + responseBody, e);
        }

        // Persist the job row so the BatchJobPoller cron can pick it up.
        // Tenant context is required — surface a clear error rather than
        // writing tenant_id=0 when MetaContext is not bound.
        Long tenantId = MetaContext.get().getTenantId();
        Long createdBy = MetaContext.get().getUserId();
        String pid = UniqueIdGenerator.generate();
        jdbc.update(
                "INSERT INTO ab_agent_batch_job "
                        + "  (pid, tenant_id, batch_id, purpose, request_count, status, "
                        + "   submitted_at, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NOW(), ?)",
                pid, tenantId, batchId, purpose, requests.size(),
                STATUS_SUBMITTED, createdBy);

        log.info("Anthropic batch submitted: id={} purpose={} requests={} tenant={}",
                batchId, purpose, requests.size(), tenantId);
        return batchId;
    }

    /**
     * Fetch the current status of a batch.
     *
     * <p>Wire shape: {@code GET {baseUrl}/v1/messages/batches/{id}}.
     *
     * @throws RuntimeException on HTTP error or parse failure.
     */
    public BatchStatus getBatch(String batchId) {
        requireBatchId(batchId);
        AgentProperties.Anthropic cfg = agentProperties.getAnthropic();
        requireApiKey(cfg);

        SsrfValidator.validate(cfg.getBaseUrl() + "/v1/messages/batches/" + batchId); // SEC-20260723-05
        String responseBody = webClient.get()
                .uri(cfg.getBaseUrl() + "/v1/messages/batches/" + batchId)
                .header("x-api-key", cfg.getApiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("anthropic-beta", ANTHROPIC_BETA)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        try {
            return objectMapper.readValue(responseBody, BatchStatus.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse batch status response: " + responseBody, e);
        }
    }

    /**
     * Stream the results of a finished batch as a list of {@link BatchResult}.
     *
     * <p>Wire shape: {@code GET {baseUrl}/v1/messages/batches/{id}/results}
     * returns JSONL — one JSON object per line. We parse each non-blank line
     * into a {@link BatchResult}; malformed lines bubble up as a
     * {@link RuntimeException} so a corrupt stream is not silently truncated.
     *
     * <p>Caller responsibility: only call after {@link #getBatch(String)}
     * reports {@code processingStatus == "ended"}. Calling earlier yields a
     * 4xx which propagates as a WebClient exception.
     */
    public List<BatchResult> getResults(String batchId) {
        requireBatchId(batchId);
        AgentProperties.Anthropic cfg = agentProperties.getAnthropic();
        requireApiKey(cfg);

        SsrfValidator.validate(cfg.getBaseUrl() + "/v1/messages/batches/" + batchId + "/results"); // SEC-20260723-05
        String responseBody = webClient.get()
                .uri(cfg.getBaseUrl() + "/v1/messages/batches/" + batchId + "/results")
                .header("x-api-key", cfg.getApiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("anthropic-beta", ANTHROPIC_BETA)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        return parseJsonl(responseBody);
    }

    /**
     * Package-visible JSONL parser — extracted so unit tests can verify the
     * line-splitting + per-line parsing behaviour without spinning up a
     * WebClient.
     *
     * <p>Anthropic returns either {@code \n} or {@code \r\n} line terminators
     * depending on the upstream gateway; we split on either. Empty lines are
     * skipped. A malformed line throws {@link RuntimeException} with the
     * offending line number so the caller can correlate with the upstream
     * stream.
     */
    List<BatchResult> parseJsonl(String body) {
        List<BatchResult> out = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return out;
        }
        // -1 keeps trailing empty strings out of the way; we filter empties below.
        String[] lines = body.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.isBlank()) continue;
            try {
                out.add(objectMapper.readValue(line, BatchResult.class));
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse batch JSONL line " + (i + 1)
                        + ": " + line, e);
            }
        }
        return out;
    }

    private static void requireApiKey(AgentProperties.Anthropic cfg) {
        if (cfg == null || cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            throw new IllegalStateException(
                    "agent.anthropic.api-key is not configured — batch API requires a valid key");
        }
    }

    private static void requireBatchId(String batchId) {
        if (batchId == null || batchId.isBlank()) {
            throw new IllegalArgumentException("batchId is required");
        }
    }
}
