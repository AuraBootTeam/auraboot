package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.decision.dto.DrtEvaluateRequest;
import com.auraboot.framework.decision.dto.DrtLogDTO;
import com.auraboot.framework.decision.dto.DrtTestRunRequest;
import com.auraboot.framework.decision.dto.DrtValidateRequest;
import com.auraboot.framework.decision.entity.DrtLogEntity;
import com.auraboot.framework.decision.entity.DrtVersionEntity;
import com.auraboot.framework.decision.mapper.DrtLogMapper;
import com.auraboot.framework.decision.mapper.DrtVersionMapper;
import com.auraboot.framework.decision.model.DecisionEvaluateOptions;
import com.auraboot.framework.decision.model.DecisionKind;
import com.auraboot.framework.decision.model.DecisionResult;
import com.auraboot.framework.decision.model.DecisionStatus;
import com.auraboot.framework.decision.model.DecisionValidateResult;
import com.auraboot.framework.decision.model.RuntimeAdapter;
import com.auraboot.framework.decision.model.VersionBinding;
import com.auraboot.framework.decision.model.VersionStatus;
import com.auraboot.framework.decision.runtime.DecisionRuntime;
import com.auraboot.framework.decision.runtime.ResolvedDecision;
import com.auraboot.framework.decision.service.DecisionEvaluationService;
import com.auraboot.framework.exception.ValidationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Decision evaluation service implementation.
 *
 * <p>§8 compliance: no {@code catch(Exception){log.warn}} swallowing; real errors surface
 * through the call chain. Log entry is always written (status=ERROR on exception).
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DecisionEvaluationServiceImpl implements DecisionEvaluationService {

    private final DrtVersionMapper versionMapper;
    private final DrtLogMapper logMapper;
    private final DecisionRuntime decisionRuntime;
    private final ObjectMapper objectMapper;

    // ─── public API ──────────────────────────────────────────────────────────

    @Transactional
    @Override
    public DecisionResult evaluate(DrtEvaluateRequest request) {
        Long tid = requireTenant();

        DrtVersionEntity versionEntity = resolveVersion(tid, request);
        DecisionContext ctx = buildContext(request.getContext());
        ResolvedDecision resolved = toResolved(versionEntity);

        long start = System.currentTimeMillis();
        DecisionResult result = decisionRuntime.evaluate(resolved, ctx, DecisionEvaluateOptions.defaults());
        long durationMs = System.currentTimeMillis() - start;

        // Log under the SAME traceId the runtime stamped on the result, so callers can correlate
        // result.traceId() -> ab_drt_log (the §22 audit contract). Generating a separate id here
        // would orphan the log from the returned result.
        writelog(tid, result.traceId(), request, versionEntity, result, durationMs);

        log.info("Decision evaluated: code={}, version={}, matched={}, durationMs={}",
                versionEntity.getDecisionCode(), versionEntity.getVersion(),
                result.matched(), durationMs);

        return result;
    }

    @Override
    public DecisionResult testRun(DrtTestRunRequest request) {
        // In-memory only — no log, no resolved version from DB
        DecisionKind kind = parseEnum(DecisionKind.class, request.getKind(), "kind");
        RuntimeAdapter adapter = parseEnum(RuntimeAdapter.class, request.getRuntimeAdapter(), "runtimeAdapter");

        ResolvedDecision resolved = new ResolvedDecision(
                "__test__", 0, null, VersionStatus.DRAFT,
                kind, adapter, request.getContentJson());

        DecisionContext ctx = buildContext(request.getContext());

        log.info("Decision test-run: kind={}, adapter={}", request.getKind(), request.getRuntimeAdapter());
        return decisionRuntime.testRun(resolved, ctx, DecisionEvaluateOptions.defaults());
    }

    @Override
    public DecisionValidateResult validate(DrtValidateRequest request) {
        DecisionKind kind = parseEnum(DecisionKind.class, request.getKind(), "kind");
        RuntimeAdapter adapter = parseEnum(RuntimeAdapter.class, request.getRuntimeAdapter(), "runtimeAdapter");

        ResolvedDecision resolved = new ResolvedDecision(
                "__validate__", 0, null, VersionStatus.DRAFT,
                kind, adapter, request.getContentJson());

        return decisionRuntime.validate(resolved);
    }

    @Override
    public List<DrtLogDTO> findLogsByTraceId(String traceId) {
        return logMapper.findByTraceId(traceId).stream()
                .map(this::toLogDTO)
                .collect(Collectors.toList());
    }

    // ─── version resolution ──────────────────────────────────────────────────

    private DrtVersionEntity resolveVersion(Long tid, DrtEvaluateRequest request) {
        VersionBinding binding = request.getBinding() != null ? request.getBinding() : VersionBinding.LATEST;

        DrtVersionEntity entity = switch (binding) {
            case LATEST -> versionMapper.findPublished(tid, request.getDecisionCode());
            case FIXED_VERSION -> {
                if (request.getFixedVersion() == null) {
                    throw new ValidationException(ResponseCode.CommonValidationFailed,
                            "fixedVersion is required when binding=FIXED_VERSION");
                }
                yield versionMapper.findByTenantCodeVersion(tid, request.getDecisionCode(), request.getFixedVersion());
            }
            default -> throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Unsupported binding strategy: " + binding);
        };

        if (entity == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND,
                    "No published version found for decision: " + request.getDecisionCode());
        }

        VersionStatus status = VersionStatus.valueOf(entity.getStatus());
        if (!status.isBindable()) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Decision version is not bindable (status=" + status + ")");
        }
        return entity;
    }

    // ─── context building ────────────────────────────────────────────────────

    private DecisionContext buildContext(Map<String, Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            return DecisionContext.of(Map.of());
        }
        DecisionContext.Builder builder = DecisionContext.builder();
        for (Map.Entry<String, Map<String, Object>> entry : raw.entrySet()) {
            try {
                Scope scope = Scope.valueOf(entry.getKey().toUpperCase());
                builder.scope(scope, entry.getValue());
            } catch (IllegalArgumentException ignored) {
                // Unknown scope names are silently skipped; callers should use canonical names.
                log.debug("Skipping unknown context scope: {}", entry.getKey());
            }
        }
        return builder.build();
    }

    // ─── ResolvedDecision ────────────────────────────────────────────────────

    private ResolvedDecision toResolved(DrtVersionEntity e) {
        DecisionKind kind = parseEnum(DecisionKind.class, e.getKind(), "kind");
        RuntimeAdapter adapter = parseEnum(RuntimeAdapter.class, e.getRuntimeAdapter(), "runtimeAdapter");
        VersionStatus status = VersionStatus.valueOf(e.getStatus());
        return new ResolvedDecision(e.getDecisionCode(), e.getVersion(), e.getVersionTag(),
                status, kind, adapter, e.getContentJson());
    }

    // ─── audit log ───────────────────────────────────────────────────────────

    private void writelog(Long tid, String traceId,
                          DrtEvaluateRequest request,
                          DrtVersionEntity ver,
                          DecisionResult result,
                          long durationMs) {
        String logStatus = mapStatus(result.status());

        DrtLogEntity logEntry = new DrtLogEntity();
        logEntry.setPid(UniqueIdGenerator.generate());
        logEntry.setTenantId(tid);
        logEntry.setTraceId(traceId);
        logEntry.setCorrelationId(request.getCorrelationId());
        logEntry.setDecisionCode(ver.getDecisionCode());
        logEntry.setDecisionVersion(ver.getVersion());
        logEntry.setKind(ver.getKind());
        logEntry.setRuntimeAdapter(ver.getRuntimeAdapter());
        logEntry.setCallerType(request.getCallerType());
        logEntry.setCallerRef(request.getCallerRef());
        logEntry.setInputDigest(sha256(safeJson(request.getContext())));
        logEntry.setResultDigest(sha256(safeJson(result)));
        logEntry.setMatched(result.matched());
        logEntry.setStatus(logStatus);
        logEntry.setDurationMs(durationMs);
        logEntry.setCreatedAt(Instant.now());

        if (!result.matchedRules().isEmpty()) {
            logEntry.setMatchedRulesJson(objectMapper.valueToTree(result.matchedRules()));
        }
        if (result.status() == DecisionStatus.ERROR && !result.errors().isEmpty()) {
            logEntry.setErrorMessage(String.join("; ", result.errors()));
        }

        logMapper.insert(logEntry);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private <T extends Enum<T>> T parseEnum(Class<T> cls, String value, String fieldName) {
        try {
            return Enum.valueOf(cls, value);
        } catch (IllegalArgumentException e) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Invalid " + fieldName + ": " + value);
        }
    }

    private String mapStatus(DecisionStatus status) {
        return switch (status) {
            case MATCHED -> "MATCHED";
            case NOT_MATCHED -> "NOT_MATCHED";
            case ERROR -> "ERROR";
            case SKIPPED -> "SKIPPED";
            default -> "UNKNOWN";
        };
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String safeJson(Object o) {
        if (o == null) return "null";
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return o.toString();
        }
    }

    private Long requireTenant() {
        Long tid = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        if (tid == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Tenant context required");
        }
        return tid;
    }

    private DrtLogDTO toLogDTO(DrtLogEntity e) {
        if (e == null) return null;
        DrtLogDTO dto = new DrtLogDTO();
        dto.setId(e.getId());
        dto.setPid(e.getPid());
        dto.setTenantId(e.getTenantId());
        dto.setTraceId(e.getTraceId());
        dto.setCorrelationId(e.getCorrelationId());
        dto.setDecisionCode(e.getDecisionCode());
        dto.setDecisionVersion(e.getDecisionVersion());
        dto.setKind(e.getKind());
        dto.setRuntimeAdapter(e.getRuntimeAdapter());
        dto.setCallerType(e.getCallerType());
        dto.setCallerRef(e.getCallerRef());
        dto.setInputDigest(e.getInputDigest());
        dto.setResultDigest(e.getResultDigest());
        dto.setMatched(e.getMatched());
        dto.setStatus(e.getStatus());
        dto.setMatchedRulesJson(e.getMatchedRulesJson());
        dto.setDurationMs(e.getDurationMs());
        dto.setErrorCode(e.getErrorCode());
        dto.setErrorMessage(e.getErrorMessage());
        dto.setCreatedAt(e.getCreatedAt());
        return dto;
    }
}
