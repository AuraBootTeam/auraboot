package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.PageResult;
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
import com.auraboot.framework.decision.model.DecisionRolloutSelection;
import com.auraboot.framework.decision.model.DecisionKind;
import com.auraboot.framework.decision.model.DecisionResult;
import com.auraboot.framework.decision.model.DecisionStatus;
import com.auraboot.framework.decision.model.DecisionValidateResult;
import com.auraboot.framework.decision.model.RuntimeAdapter;
import com.auraboot.framework.decision.model.VersionBinding;
import com.auraboot.framework.decision.model.VersionStatus;
import com.auraboot.framework.decision.runtime.DecisionRuntime;
import com.auraboot.framework.decision.runtime.ResolvedDecision;
import com.auraboot.framework.decision.runtime.VersionSelector;
import com.auraboot.framework.decision.service.DecisionEvaluationService;
import com.auraboot.framework.decision.service.DecisionRolloutService;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    private final DecisionRolloutService rolloutService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<DynamicDataService> dynamicDataServiceProvider;
    private final MetaModelMapper metaModelMapper;
    private final PlatformTransactionManager transactionManager;

    // ─── public API ──────────────────────────────────────────────────────────

    @Transactional
    @Override
    public DecisionResult evaluate(DrtEvaluateRequest request) {
        Long tid = requireTenant();

        Resolution resolution = resolveVersion(tid, request);
        DrtVersionEntity versionEntity = resolution.version();
        ContextBuildResult contextBuild = buildContextWithTrace(request.getContext());
        DecisionContext ctx = contextBuild.context();
        ResolvedDecision resolved = toResolved(versionEntity);

        long start = System.currentTimeMillis();
        DecisionResult result = decisionRuntime.evaluate(resolved, ctx, DecisionEvaluateOptions.defaults());
        long durationMs = System.currentTimeMillis() - start;

        // Log under the SAME traceId the runtime stamped on the result, so callers can correlate
        // result.traceId() -> ab_drt_log (the §22 audit contract). Generating a separate id here
        // would orphan the log from the returned result.
        writelog(tid, result.traceId(), request, versionEntity, result, durationMs,
                resolution.rollout(), contextBuild.virtualSourceTrace());

        log.info("Decision evaluated: code={}, version={}, matched={}, durationMs={}",
                versionEntity.getDecisionCode(), versionEntity.getVersion(),
                result.matched(), durationMs);

        return result;
    }

    @Override
    public List<DecisionResult> batchEvaluate(List<DrtEvaluateRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        List<DecisionResult> results = new java.util.ArrayList<>(requests.size());
        for (DrtEvaluateRequest req : requests) {
            try {
                results.add(evaluate(req));
            } catch (RuntimeException e) {
                // one bad request must not fail the whole batch (docs/1.md §24.3)
                String code = req != null ? req.getDecisionCode() : "__batch_invalid__";
                results.add(DecisionResult.builder(code)
                        .status(com.auraboot.framework.decision.model.DecisionStatus.ERROR)
                        .matched(false)
                        .errors(List.of(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()))
                        .build());
            }
        }
        return results;
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
        Long tid = requireTenant();
        return logMapper.findByTraceId(tid, traceId).stream()
                .map(this::toLogDTO)
                .collect(Collectors.toList());
    }

    @Override
    public DrtLogDTO findLogByPid(String pid) {
        Long tid = requireTenant();
        return toLogDTO(logMapper.findByPid(tid, pid));
    }

    @Override
    public PageResult<DrtLogDTO> findRecentLogs(
            String keyword,
            String decisionCode,
            String status,
            String callerType,
            String callerRef,
            Boolean matched,
            String rolloutArm,
            Long minDurationMs,
            Long maxDurationMs,
            int page,
            int size) {
        Long tid = requireTenant();
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(1, Math.min(size, 100));

        LambdaQueryWrapper<DrtLogEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DrtLogEntity::getTenantId, tid);
        if (StringUtils.hasText(decisionCode)) {
            wrapper.like(DrtLogEntity::getDecisionCode, decisionCode.trim());
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(DrtLogEntity::getStatus, status.trim().toUpperCase());
        }
        if (StringUtils.hasText(callerType)) {
            wrapper.eq(DrtLogEntity::getCallerType, callerType.trim().toUpperCase());
        }
        if (StringUtils.hasText(callerRef)) {
            wrapper.eq(DrtLogEntity::getCallerRef, callerRef.trim());
        }
        if (matched != null) {
            wrapper.eq(DrtLogEntity::getMatched, matched);
        }
        if (StringUtils.hasText(rolloutArm)) {
            wrapper.eq(DrtLogEntity::getRolloutArm, rolloutArm.trim().toUpperCase());
        }
        if (minDurationMs != null) {
            wrapper.ge(DrtLogEntity::getDurationMs, Math.max(0L, minDurationMs));
        }
        if (maxDurationMs != null) {
            wrapper.le(DrtLogEntity::getDurationMs, Math.max(0L, maxDurationMs));
        }
        if (StringUtils.hasText(keyword)) {
            String q = keyword.trim();
            wrapper.and(w -> w.like(DrtLogEntity::getTraceId, q)
                    .or().like(DrtLogEntity::getCorrelationId, q)
                    .or().like(DrtLogEntity::getDecisionCode, q)
                    .or().like(DrtLogEntity::getStatus, q)
                    .or().like(DrtLogEntity::getCallerType, q)
                    .or().like(DrtLogEntity::getCallerRef, q)
                    .or().like(DrtLogEntity::getRolloutArm, q)
                    .or().like(DrtLogEntity::getRoutingKey, q)
                    .or().like(DrtLogEntity::getErrorMessage, q));
        }
        wrapper.orderByDesc(DrtLogEntity::getCreatedAt);

        Page<DrtLogEntity> entityPage = logMapper.selectPage(new Page<>(safePage + 1L, safeSize), wrapper);
        PageResult<DrtLogDTO> result = new PageResult<>();
        result.setRecords(entityPage.getRecords().stream().map(this::toLogDTO).toList());
        result.setTotal(entityPage.getTotal());
        result.setSize(entityPage.getSize());
        result.setCurrent(entityPage.getCurrent());
        result.setPages(entityPage.getPages());
        result.setHasPrevious(entityPage.hasPrevious());
        result.setHasNext(entityPage.hasNext());
        return result;
    }

    // ─── version resolution ──────────────────────────────────────────────────

    private record Resolution(DrtVersionEntity version, DecisionRolloutSelection rollout) {}

    private Resolution resolveVersion(Long tid, DrtEvaluateRequest request) {
        VersionBinding binding = request.getBinding() != null ? request.getBinding() : VersionBinding.LATEST;

        // Required-criteria checks per binding (clear error rather than a silent null/UNKNOWN).
        if ((binding == VersionBinding.FIXED_VERSION || binding == VersionBinding.DEPLOYMENT_VERSION)
                && request.getFixedVersion() == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "fixedVersion is required when binding=" + binding);
        }
        if (binding == VersionBinding.VERSION_TAG && request.getVersionTag() == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "versionTag is required when binding=VERSION_TAG");
        }
        if ((binding == VersionBinding.EFFECTIVE_TIME || binding == VersionBinding.AS_OF_EVENT_TIME)
                && request.getAsOf() == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "asOf is required when binding=" + binding);
        }

        java.util.List<DrtVersionEntity> candidates = versionMapper.findAllByCode(tid, request.getDecisionCode());
        if (binding == VersionBinding.ROLLOUT) {
            DecisionRolloutSelection selection = rolloutService.select(tid, request, candidates);
            return new Resolution(selection.selectedVersion(), selection);
        }
        DrtVersionEntity entity = VersionSelector.select(candidates, binding,
                VersionSelector.Criteria.of(request.getFixedVersion(), request.getVersionTag(), request.getAsOf()));

        if (entity == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND,
                    "No version found for decision " + request.getDecisionCode() + " with binding=" + binding);
        }

        VersionStatus status = VersionStatus.valueOf(entity.getStatus());
        if (!status.isBindable()) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Decision version is not bindable (status=" + status + ")");
        }
        return new Resolution(entity, null);
    }

    // ─── context building ────────────────────────────────────────────────────

    private DecisionContext buildContext(Map<String, Map<String, Object>> raw) {
        return buildContextWithTrace(raw).context();
    }

    private ContextBuildResult buildContextWithTrace(Map<String, Map<String, Object>> raw) {
        EnrichedContext enriched = enrichVirtualSources(raw);
        Map<String, Map<String, Object>> resolved = enriched.context();
        if (resolved == null || resolved.isEmpty()) {
            return new ContextBuildResult(DecisionContext.of(Map.of()), enriched.virtualSourceTrace());
        }
        DecisionContext.Builder builder = DecisionContext.builder();
        for (Map.Entry<String, Map<String, Object>> entry : resolved.entrySet()) {
            try {
                Scope scope = Scope.valueOf(entry.getKey().toUpperCase());
                builder.scope(scope, entry.getValue());
            } catch (IllegalArgumentException ignored) {
                // Unknown scope names are silently skipped; callers should use canonical names.
                log.debug("Skipping unknown context scope: {}", entry.getKey());
            }
        }
        return new ContextBuildResult(builder.build(), enriched.virtualSourceTrace());
    }

    private EnrichedContext enrichVirtualSources(Map<String, Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            return new EnrichedContext(raw, List.of());
        }
        Map<String, Object> metaScope = findScope(raw, Scope.META.code());
        List<Map<String, Object>> selectors = virtualSourceSelectors(metaScope);
        if (selectors.isEmpty()) {
            return new EnrichedContext(raw, List.of());
        }

        DynamicDataService dynamicDataService = dynamicDataServiceProvider.getIfAvailable();
        if (dynamicDataService == null) {
            log.debug("Skipping virtual source resolution because DynamicDataService is unavailable");
            return new EnrichedContext(raw, selectors.stream()
                    .map(selector -> virtualSourceTrace(selector, null, "SKIPPED",
                            "DynamicDataService unavailable", Map.of()))
                    .toList());
        }

        Map<String, Map<String, Object>> copy = mutableContextCopy(raw);
        Map<String, Object> recordData = ensureRecordDataScope(copy);
        List<Map<String, Object>> trace = new ArrayList<>();
        for (Map<String, Object> selector : selectors) {
            trace.add(resolveVirtualSource(selector, dynamicDataService, recordData));
        }
        return new EnrichedContext(copy, trace);
    }

    private Map<String, Object> resolveVirtualSource(Map<String, Object> selector,
                                                     DynamicDataService dynamicDataService,
                                                     Map<String, Object> recordData) {
        String sourceRef = stringValue(selector.get("sourceRef"));
        String recordId = firstText(selector, "recordId", "recordPid", "id", "primaryKeyValue");
        String modelCode = resolveVirtualModelCode(selector, sourceRef);
        if (!StringUtils.hasText(modelCode) || !StringUtils.hasText(recordId)) {
            log.debug("Skipping virtual source selector without resolvable model/record: sourceRef={}, modelCode={}",
                    sourceRef, stringValue(selector.get("modelCode")));
            return virtualSourceTrace(selector, modelCode, "SKIPPED", "Missing resolvable model or record", Map.of());
        }

        try {
            Map<String, Object> row = resolveVirtualSourceRow(dynamicDataService, modelCode, recordId);
            if (row == null || row.isEmpty()) {
                return virtualSourceTrace(selector, modelCode, "MISSING", "No virtual source row", Map.of());
            }
            Map<String, Object> resolvedFields = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (StringUtils.hasText(entry.getKey()) && !recordData.containsKey(entry.getKey())) {
                    recordData.put(entry.getKey(), entry.getValue());
                    if (isTraceableVirtualField(entry.getKey())) {
                        resolvedFields.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            String status = resolvedFields.isEmpty() ? "NO_NEW_FIELDS" : "RESOLVED";
            return virtualSourceTrace(selector, modelCode, status, null, resolvedFields);
        } catch (MetaServiceException | DataAccessException e) {
            // Fail closed into UNKNOWN: unresolved virtual facts stay missing, so the AST evaluator
            // reports unknownReasons instead of matching a rule with partial external data.
            log.debug("Virtual source resolver left sourceRef={} modelCode={} recordId={} unresolved: {}",
                    sourceRef, modelCode, recordId, e.getMessage());
            return virtualSourceTrace(selector, modelCode, "ERROR", e.getMessage(), Map.of());
        }
    }

    private Map<String, Object> virtualSourceTrace(Map<String, Object> selector,
                                                   String modelCode,
                                                   String status,
                                                   String reason,
                                                   Map<String, Object> fields) {
        Map<String, Object> trace = new LinkedHashMap<>();
        putIfPresent(trace, "sourceRef", stringValue(selector.get("sourceRef")));
        putIfPresent(trace, "modelCode", StringUtils.hasText(modelCode) ? modelCode : stringValue(selector.get("modelCode")));
        putIfPresent(trace, "recordId", firstText(selector, "recordId", "recordPid", "id", "primaryKeyValue"));
        trace.put("status", status);
        if (StringUtils.hasText(reason)) {
            trace.put("reason", reason);
        }
        trace.put("fields", fields == null ? Map.of() : fields);
        return trace;
    }

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value);
        }
    }

    private boolean isTraceableVirtualField(String field) {
        return !"id".equalsIgnoreCase(field)
                && !"tenant_id".equalsIgnoreCase(field)
                && !"tenantId".equals(field)
                && !"deleted_flag".equalsIgnoreCase(field);
    }

    private Map<String, Object> resolveVirtualSourceRow(DynamicDataService dynamicDataService,
                                                        String modelCode,
                                                        String recordId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        tx.setReadOnly(true);
        return tx.execute(status -> dynamicDataService.getById(modelCode, recordId));
    }

    private String resolveVirtualModelCode(Map<String, Object> selector, String sourceRef) {
        String explicitModelCode = stringValue(selector.get("modelCode"));
        if (StringUtils.hasText(explicitModelCode)) {
            Model explicit = metaModelMapper.findCurrentByCode(explicitModelCode);
            if (isPublishedVirtualModel(explicit)
                    && (!StringUtils.hasText(sourceRef) || sourceRef.equals(explicit.getSourceRef()))) {
                return explicit.getCode();
            }
            log.debug("Skipping explicit virtual model selector because model is not a published matching virtual model: {}",
                    explicitModelCode);
            return null;
        }
        if (!StringUtils.hasText(sourceRef)) {
            return null;
        }
        List<Model> matches = metaModelMapper.findCurrentByTenant().stream()
                .filter(this::isPublishedVirtualModel)
                .filter(model -> sourceRef.equals(model.getSourceRef()))
                .toList();
        if (matches.size() == 1) {
            return matches.get(0).getCode();
        }
        if (matches.size() > 1) {
            log.debug("Skipping ambiguous virtual sourceRef={} with {} current published models",
                    sourceRef, matches.size());
        }
        return null;
    }

    private boolean isPublishedVirtualModel(Model model) {
        return model != null
                && model.isPublished()
                && StringUtils.hasText(model.getSourceType())
                && !"physical".equalsIgnoreCase(model.getSourceType())
                && StringUtils.hasText(model.getSourceRef());
    }

    private List<Map<String, Object>> virtualSourceSelectors(Map<String, Object> metaScope) {
        if (metaScope == null || metaScope.isEmpty()) {
            return List.of();
        }
        Object rawSources = metaScope.get("virtualSources");
        if (rawSources == null) {
            rawSources = metaScope.get("virtualSource");
        }
        if (rawSources == null) {
            return List.of();
        }

        List<Map<String, Object>> selectors = new ArrayList<>();
        if (rawSources instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item instanceof Map<?, ?> map) {
                    selectors.add(stringKeyMap(map));
                }
            }
            return selectors;
        }
        if (rawSources instanceof Map<?, ?> map) {
            Map<String, Object> candidate = stringKeyMap(map);
            if (looksLikeVirtualSourceSelector(candidate)) {
                selectors.add(candidate);
                return selectors;
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String sourceRef = stringValue(entry.getKey());
                if (!StringUtils.hasText(sourceRef)) {
                    continue;
                }
                Map<String, Object> selector = new LinkedHashMap<>();
                selector.put("sourceRef", sourceRef);
                if (entry.getValue() instanceof Map<?, ?> valueMap) {
                    selector.putAll(stringKeyMap(valueMap));
                    selector.putIfAbsent("sourceRef", sourceRef);
                } else {
                    selector.put("recordId", entry.getValue());
                }
                selectors.add(selector);
            }
        }
        return selectors;
    }

    private boolean looksLikeVirtualSourceSelector(Map<String, Object> candidate) {
        return candidate.containsKey("sourceRef")
                || candidate.containsKey("modelCode")
                || candidate.containsKey("recordId")
                || candidate.containsKey("recordPid")
                || candidate.containsKey("primaryKeyValue");
    }

    private Map<String, Map<String, Object>> mutableContextCopy(Map<String, Map<String, Object>> raw) {
        Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : raw.entrySet()) {
            copy.put(entry.getKey(), entry.getValue() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(entry.getValue()));
        }
        return copy;
    }

    private Map<String, Object> ensureRecordDataScope(Map<String, Map<String, Object>> context) {
        Map<String, Object> recordScope = findScope(context, Scope.RECORD.code());
        if (recordScope == null) {
            recordScope = new LinkedHashMap<>();
            context.put(Scope.RECORD.code(), recordScope);
        }
        Object data = recordScope.get("data");
        Map<String, Object> mutableData = data instanceof Map<?, ?> map
                ? stringKeyMap(map)
                : new LinkedHashMap<>();
        recordScope.put("data", mutableData);
        return mutableData;
    }

    private Map<String, Object> findScope(Map<String, Map<String, Object>> context, String scopeCode) {
        if (context == null) {
            return null;
        }
        for (Map.Entry<String, Map<String, Object>> entry : context.entrySet()) {
            if (entry.getKey() != null
                    && entry.getKey().toLowerCase(Locale.ROOT).equals(scopeCode.toLowerCase(Locale.ROOT))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private Map<String, Object> stringKeyMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String key = stringValue(entry.getKey());
            if (StringUtils.hasText(key)) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    private String firstText(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            String value = stringValue(source.get(key));
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString().trim();
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
                          long durationMs,
                          DecisionRolloutSelection rollout,
                          List<Map<String, Object>> virtualSourceTrace) {
        String logStatus = mapStatus(result.status());

        DrtLogEntity logEntry = new DrtLogEntity();
        logEntry.setPid(UniqueIdGenerator.generate());
        logEntry.setTenantId(tid);
        logEntry.setTraceId(traceId);
        logEntry.setCorrelationId(request.getCorrelationId());
        logEntry.setDecisionCode(ver.getDecisionCode());
        logEntry.setDecisionVersion(ver.getVersion());
        logEntry.setSelectedVersion(ver.getVersion());
        if (rollout != null) {
            logEntry.setRolloutPolicyPid(rollout.policy().getPid());
            logEntry.setRolloutBucket(rollout.bucket());
            logEntry.setRolloutArm(rollout.arm().name());
            logEntry.setRoutingKey(rollout.routingKey());
            logEntry.setRolloutResultKey(rolloutResultKey(result));
        }
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
        if (result.outputs() != null && !result.outputs().isEmpty()) {
            logEntry.setOutputSnapshot(objectMapper.valueToTree(result.outputs()));
        }
        Map<String, Object> traceSnapshot = buildTraceSnapshot(result, virtualSourceTrace);
        if (!traceSnapshot.isEmpty()) {
            logEntry.setTraceSnapshot(objectMapper.valueToTree(traceSnapshot));
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
        dto.setSelectedVersion(e.getSelectedVersion());
        dto.setRolloutPolicyPid(e.getRolloutPolicyPid());
        dto.setRolloutBucket(e.getRolloutBucket());
        dto.setRolloutArm(e.getRolloutArm());
        dto.setRoutingKey(e.getRoutingKey());
        dto.setRolloutResultKey(e.getRolloutResultKey());
        dto.setKind(e.getKind());
        dto.setRuntimeAdapter(e.getRuntimeAdapter());
        dto.setCallerType(e.getCallerType());
        dto.setCallerRef(e.getCallerRef());
        dto.setInputDigest(e.getInputDigest());
        dto.setResultDigest(e.getResultDigest());
        dto.setMatched(e.getMatched());
        dto.setStatus(e.getStatus());
        dto.setMatchedRulesJson(e.getMatchedRulesJson());
        dto.setOutputSnapshot(e.getOutputSnapshot());
        dto.setTraceSnapshot(e.getTraceSnapshot());
        dto.setDurationMs(e.getDurationMs());
        dto.setErrorCode(e.getErrorCode());
        dto.setErrorMessage(e.getErrorMessage());
        dto.setCreatedAt(e.getCreatedAt());
        return dto;
    }

    private Map<String, Object> buildTraceSnapshot(
            DecisionResult result,
            List<Map<String, Object>> virtualSourceTrace) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (virtualSourceTrace != null && !virtualSourceTrace.isEmpty()) {
            snapshot.put("virtualSources", virtualSourceTrace);
        }
        if (result != null && result.unknownReasons() != null && !result.unknownReasons().isEmpty()) {
            snapshot.put("unknownReasons", result.unknownReasons());
        }
        return snapshot;
    }

    private record ContextBuildResult(
            DecisionContext context,
            List<Map<String, Object>> virtualSourceTrace) {}

    private record EnrichedContext(
            Map<String, Map<String, Object>> context,
            List<Map<String, Object>> virtualSourceTrace) {}

    private String rolloutResultKey(DecisionResult result) {
        if (result == null) {
            return "null";
        }
        if (result.outputs() == null || result.outputs().isEmpty()) {
            return result.status().name();
        }
        String key = result.outputs().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + String.valueOf(entry.getValue()))
                .collect(Collectors.joining(","));
        return key.length() <= 200 ? key : key.substring(0, 200);
    }
}
