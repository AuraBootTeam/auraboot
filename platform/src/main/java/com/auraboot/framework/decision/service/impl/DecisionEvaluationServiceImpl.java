package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.decision.dto.DecisionFactCatalogDTO;
import com.auraboot.framework.decision.dto.DecisionFactDTO;
import com.auraboot.framework.decision.dto.DecisionFactEntityDTO;
import com.auraboot.framework.decision.dto.DecisionFactOptionDTO;
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
import com.auraboot.framework.decision.service.DecisionModelFieldService;
import com.auraboot.framework.decision.service.DecisionRolloutService;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.user.dto.UserSearchDTO;
import com.auraboot.framework.user.service.UserService;
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

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    private static final Object MISSING_CONTEXT_VALUE = new Object();

    private final DrtVersionMapper versionMapper;
    private final DrtLogMapper logMapper;
    private final DecisionRuntime decisionRuntime;
    private final DecisionRolloutService rolloutService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<DynamicDataService> dynamicDataServiceProvider;
    private final MetaModelMapper metaModelMapper;
    private final PlatformTransactionManager transactionManager;
    private final DecisionModelFieldService decisionModelFieldService;
    private final UserService userService;

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

        Map<String, Object> factMetadata = buildFactMetadataSnapshot(
                contextBuild.resolvedContext(), result, contextBuild.virtualSourceTrace());
        // Log under the SAME traceId the runtime stamped on the result, so callers can correlate
        // result.traceId() -> ab_drt_log (the §22 audit contract). Generating a separate id here
        // would orphan the log from the returned result.
        writelog(tid, result.traceId(), request, versionEntity, result, durationMs,
                resolution.rollout(), contextBuild.virtualSourceTrace(), factMetadata);

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
            return new ContextBuildResult(DecisionContext.of(Map.of()), Map.of(), enriched.virtualSourceTrace());
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
        return new ContextBuildResult(builder.build(), resolved, enriched.virtualSourceTrace());
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
                if (StringUtils.hasText(entry.getKey())
                        && isTraceableVirtualField(entry.getKey())
                        && !recordData.containsKey(entry.getKey())) {
                    recordData.put(entry.getKey(), entry.getValue());
                    resolvedFields.put(entry.getKey(), entry.getValue());
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
        putIfPresent(trace, "recordPid", firstText(selector, "recordPid", "recordId", "pid", "id", "primaryKeyValue"));
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
                          List<Map<String, Object>> virtualSourceTrace,
                          Map<String, Object> factMetadata) {
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
        Map<String, Object> traceSnapshot = buildTraceSnapshot(result, virtualSourceTrace, factMetadata);
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
            List<Map<String, Object>> virtualSourceTrace,
            Map<String, Object> factMetadata) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (virtualSourceTrace != null && !virtualSourceTrace.isEmpty()) {
            snapshot.put("virtualSources", virtualSourceTrace);
        }
        if (result != null && result.unknownReasons() != null && !result.unknownReasons().isEmpty()) {
            snapshot.put("unknownReasons", result.unknownReasons());
        }
        if (factMetadata != null && !factMetadata.isEmpty()) {
            snapshot.put("factMetadata", factMetadata);
        }
        return snapshot;
    }

    private Map<String, Object> buildFactMetadataSnapshot(
            Map<String, Map<String, Object>> resolvedContext,
            DecisionResult result,
            List<Map<String, Object>> virtualSourceTrace) {
        Set<String> relevantKeys = new LinkedHashSet<>();
        Set<String> modelCodes = new LinkedHashSet<>();
        collectContextFactRefs(resolvedContext, relevantKeys, modelCodes);
        collectOutputFactRefs(result, relevantKeys);
        collectVirtualSourceFactRefs(virtualSourceTrace, relevantKeys, modelCodes);
        if (relevantKeys.isEmpty()) {
            return Map.of();
        }

        List<FactMetadataMatch> matchedFacts = new ArrayList<>();
        Set<String> seenFacts = new LinkedHashSet<>();
        List<String> catalogModelCodes = new ArrayList<>();
        if (modelCodes.isEmpty()) {
            catalogModelCodes.add(null);
        } else {
            catalogModelCodes.addAll(modelCodes);
        }
        for (String modelCode : catalogModelCodes) {
            DecisionFactCatalogDTO catalog;
            try {
                catalog = decisionModelFieldService.getFactCatalog(modelCode);
            } catch (RuntimeException ex) {
                log.debug("Skipping decision trace fact metadata snapshot: {}", ex.getMessage());
                continue;
            }
            if (catalog == null || catalog.getEntities() == null) {
                continue;
            }
            for (DecisionFactEntityDTO entity : catalog.getEntities()) {
                if (entity == null || entity.getFacts() == null) {
                    continue;
                }
                for (DecisionFactDTO fact : entity.getFacts()) {
                    if (fact == null) {
                        continue;
                    }
                    Set<String> aliases = factAliases(fact);
                    if (aliases.stream().noneMatch(relevantKeys::contains)) {
                        continue;
                    }
                    String factKey = String.valueOf(entity.getModelCode())
                            + "|"
                            + String.valueOf(fact.getFactKey());
                    if (seenFacts.add(factKey)) {
                        matchedFacts.add(new FactMetadataMatch(entity, fact, aliases));
                    }
                }
            }
        }
        if (matchedFacts.isEmpty()) {
            return Map.of();
        }

        Map<String, Integer> aliasCounts = new HashMap<>();
        for (FactMetadataMatch match : matchedFacts) {
            for (String alias : match.aliases()) {
                aliasCounts.merge(alias, 1, Integer::sum);
            }
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (FactMetadataMatch match : matchedFacts) {
            Map<String, Object> metadata = factMetadata(match.entity(), match.fact(), resolvedContext);
            if (metadata.isEmpty()) {
                continue;
            }
            for (String alias : match.aliases()) {
                if (aliasCounts.getOrDefault(alias, 0) > 1 && isLooseFactAlias(alias)) {
                    continue;
                }
                if (relevantKeys.contains(alias) || alias.equals(match.fact().getFactKey())) {
                    snapshot.putIfAbsent(alias, metadata);
                }
            }
        }
        return snapshot;
    }

    private void collectContextFactRefs(
            Map<String, Map<String, Object>> resolvedContext,
            Set<String> relevantKeys,
            Set<String> modelCodes) {
        if (resolvedContext == null || resolvedContext.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Map<String, Object>> scopeEntry : resolvedContext.entrySet()) {
            collectFactRefs(scopeEntry.getKey(), "", scopeEntry.getValue(), relevantKeys, modelCodes);
        }
    }

    private void collectFactRefs(
            String scope,
            String prefix,
            Object raw,
            Set<String> relevantKeys,
            Set<String> modelCodes) {
        if (raw instanceof Map<?, ?> rawMap) {
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (!(entry.getKey() instanceof String key) || !StringUtils.hasText(key)) {
                    continue;
                }
                Object value = entry.getValue();
                String path = StringUtils.hasText(prefix) ? prefix + "." + key : key;
                if (("modelCode".equals(key) || "entityCode".equals(key))
                        && value instanceof String modelCode
                        && StringUtils.hasText(modelCode)) {
                    modelCodes.add(modelCode);
                }
                addFactKeyAliases(scope, path, relevantKeys);
                if (value instanceof Map<?, ?>) {
                    collectFactRefs(scope, path, value, relevantKeys, modelCodes);
                } else if (value instanceof Iterable<?> iterable) {
                    for (Object item : iterable) {
                        collectFactRefs(scope, path, item, relevantKeys, modelCodes);
                    }
                }
            }
        }
    }

    private void collectOutputFactRefs(DecisionResult result, Set<String> relevantKeys) {
        if (result == null || result.outputs() == null || result.outputs().isEmpty()) {
            return;
        }
        for (String key : result.outputs().keySet()) {
            addLooseFactAliases(key, relevantKeys);
        }
    }

    private void collectVirtualSourceFactRefs(
            List<Map<String, Object>> virtualSourceTrace,
            Set<String> relevantKeys,
            Set<String> modelCodes) {
        if (virtualSourceTrace == null || virtualSourceTrace.isEmpty()) {
            return;
        }
        for (Map<String, Object> source : virtualSourceTrace) {
            if (source == null) {
                continue;
            }
            Object modelCode = source.get("modelCode");
            if (modelCode instanceof String code && StringUtils.hasText(code)) {
                modelCodes.add(code);
            }
            Object fields = source.get("fields");
            if (fields instanceof Map<?, ?> fieldMap) {
                for (Object key : fieldMap.keySet()) {
                    if (key instanceof String fieldKey) {
                        addLooseFactAliases(fieldKey, relevantKeys);
                    }
                }
            }
        }
    }

    private void addFactKeyAliases(String scope, String path, Set<String> aliases) {
        if (!StringUtils.hasText(path)) {
            return;
        }
        String cleanedPath = trimDotPath(path);
        if (!StringUtils.hasText(cleanedPath)) {
            return;
        }
        aliases.add(cleanedPath);
        if (StringUtils.hasText(scope)) {
            aliases.add(trimDotPath(scope + "." + cleanedPath));
        }
        int lastDot = cleanedPath.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < cleanedPath.length() - 1) {
            aliases.add(cleanedPath.substring(lastDot + 1));
        }
        if (cleanedPath.startsWith("data.")) {
            String shortPath = cleanedPath.substring("data.".length());
            aliases.add(shortPath);
            if (StringUtils.hasText(scope)) {
                aliases.add(trimDotPath(scope + "." + shortPath));
            }
        }
    }

    private void addLooseFactAliases(String key, Set<String> aliases) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        String cleanedKey = trimDotPath(key);
        if (!StringUtils.hasText(cleanedKey)) {
            return;
        }
        aliases.add(cleanedKey);
        int lastDot = cleanedKey.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < cleanedKey.length() - 1) {
            aliases.add(cleanedKey.substring(lastDot + 1));
        }
        if (!cleanedKey.contains(".")) {
            aliases.add("data." + cleanedKey);
            aliases.add("record.data." + cleanedKey);
        } else if (cleanedKey.startsWith("data.")) {
            aliases.add("record." + cleanedKey);
            aliases.add(cleanedKey.substring("data.".length()));
        } else if (cleanedKey.startsWith("record.")) {
            aliases.add(cleanedKey.substring("record.".length()));
        }
    }

    private Set<String> factAliases(DecisionFactDTO fact) {
        Set<String> aliases = new LinkedHashSet<>();
        addLooseFactAliases(fact.getFactKey(), aliases);
        addFactKeyAliases(fact.getScope(), fact.getPath(), aliases);
        return aliases;
    }

    private boolean isLooseFactAlias(String alias) {
        return StringUtils.hasText(alias) && !alias.contains(".");
    }

    private String trimDotPath(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.startsWith(".")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith(".")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private Map<String, Object> factMetadata(
            DecisionFactEntityDTO entity,
            DecisionFactDTO fact,
            Map<String, Map<String, Object>> resolvedContext) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        putText(metadata, "scope", fact.getScope());
        putText(metadata, "path", fact.getPath());
        putText(metadata, "factKey", fact.getFactKey());
        putText(metadata, "label", fact.getLabel());
        putText(metadata, "dataType", fact.getDataType());
        putText(metadata, "modelCode", fact.getModelCode());
        putText(metadata, "sourceType", fact.getSourceType());
        if (entity != null) {
            putText(metadata, "sourceRef", entity.getSourceRef());
        }
        putText(metadata, "dictCode", fact.getDictCode());
        Map<String, String> valueLabels = new LinkedHashMap<>(valueLabels(fact.getAllowedValues()));
        valueLabels.putAll(referenceValueLabels(fact, resolvedContext));
        if (!valueLabels.isEmpty()) {
            metadata.put("valueLabels", valueLabels);
        }
        if (fact.getMasked() != null) {
            metadata.put("masked", fact.getMasked());
        }
        putText(metadata, "permission", fact.getPermission());
        return metadata;
    }

    private Map<String, String> valueLabels(List<DecisionFactOptionDTO> options) {
        if (options == null || options.isEmpty()) {
            return Map.of();
        }
        Map<String, String> labels = new LinkedHashMap<>();
        for (DecisionFactOptionDTO option : options) {
            if (option == null
                    || !StringUtils.hasText(option.getValue())
                    || !StringUtils.hasText(option.getLabel())) {
                continue;
            }
            labels.put(option.getValue(), option.getLabel());
        }
        return labels;
    }

    private Map<String, String> referenceValueLabels(
            DecisionFactDTO fact,
            Map<String, Map<String, Object>> resolvedContext) {
        String targetEntity = referenceTargetEntity(fact);
        String dataType = fact.getDataType();
        if (!isReferenceLike(dataType, targetEntity)) {
            return Map.of();
        }
        Set<String> values = runtimeFactValues(fact, resolvedContext);
        if (values.isEmpty()) {
            return Map.of();
        }
        String displayField = referenceDisplayField(fact);
        Map<String, String> labels = new LinkedHashMap<>();
        for (String value : values) {
            String label = resolveReferenceLabel(targetEntity, displayField, dataType, value);
            if (StringUtils.hasText(label)) {
                labels.put(value, label);
            }
        }
        return labels;
    }

    private Set<String> runtimeFactValues(
            DecisionFactDTO fact,
            Map<String, Map<String, Object>> resolvedContext) {
        if (fact == null || resolvedContext == null || resolvedContext.isEmpty()) {
            return Set.of();
        }
        Object raw = contextValueForFact(fact, resolvedContext);
        if (raw == MISSING_CONTEXT_VALUE || raw == null) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        collectScalarValues(raw, values);
        return values;
    }

    private Object contextValueForFact(
            DecisionFactDTO fact,
            Map<String, Map<String, Object>> resolvedContext) {
        String scope = StringUtils.hasText(fact.getScope()) ? fact.getScope() : "record";
        Map<String, Object> scopeContext = resolvedContext.get(scope);
        if (scopeContext == null) {
            scopeContext = resolvedContext.get(scope.toLowerCase(Locale.ROOT));
        }
        if (scopeContext == null) {
            return MISSING_CONTEXT_VALUE;
        }

        List<String> candidates = new ArrayList<>();
        if (StringUtils.hasText(fact.getPath())) {
            candidates.add(fact.getPath());
        }
        if (StringUtils.hasText(fact.getFactKey())) {
            candidates.add(fact.getFactKey());
        }
        List<String> normalized = new ArrayList<>();
        for (String candidate : candidates) {
            String path = trimDotPath(candidate);
            if (!StringUtils.hasText(path)) {
                continue;
            }
            normalized.add(path);
            String scopedPrefix = scope + ".";
            if (path.startsWith(scopedPrefix)) {
                normalized.add(path.substring(scopedPrefix.length()));
            }
            if (path.startsWith("record.")) {
                normalized.add(path.substring("record.".length()));
            }
        }

        for (String path : normalized) {
            Object value = readPath(scopeContext, path);
            if (value != MISSING_CONTEXT_VALUE) {
                return value;
            }
        }
        return MISSING_CONTEXT_VALUE;
    }

    private Object readPath(Map<String, Object> root, String path) {
        if (!StringUtils.hasText(path)) {
            return MISSING_CONTEXT_VALUE;
        }
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (!StringUtils.hasText(segment)) {
                continue;
            }
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(segment)) {
                return MISSING_CONTEXT_VALUE;
            }
            current = map.get(segment);
        }
        return current;
    }

    private void collectScalarValues(Object raw, Set<String> values) {
        if (raw == null) {
            return;
        }
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectScalarValues(item, values);
            }
            return;
        }
        if (raw.getClass().isArray()) {
            int length = Array.getLength(raw);
            for (int i = 0; i < length; i++) {
                collectScalarValues(Array.get(raw, i), values);
            }
            return;
        }
        if (raw instanceof Map<?, ?> map) {
            for (String key : List.of("value", "pid", "id")) {
                Object value = map.get(key);
                if (value != null) {
                    collectScalarValues(value, values);
                    return;
                }
            }
            return;
        }
        String value = String.valueOf(raw);
        if (StringUtils.hasText(value)) {
            values.add(value);
        }
    }

    private String resolveReferenceLabel(
            String targetEntity,
            String displayField,
            String dataType,
            String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        if (isUserTarget(targetEntity, dataType)) {
            return resolveUserLabel(value);
        }
        if (!StringUtils.hasText(targetEntity)) {
            return null;
        }
        DynamicDataService dynamicDataService = dynamicDataServiceProvider.getIfAvailable();
        if (dynamicDataService == null) {
            return null;
        }
        try {
            Map<String, Object> record = dynamicDataService.getById(targetEntity, value);
            return firstMapText(
                    record,
                    displayField,
                    "displayName",
                    "name",
                    "title",
                    "code",
                    "pid",
                    "id");
        } catch (RuntimeException ex) {
            log.debug("Skipping reference value label resolution for {}:{}: {}",
                    targetEntity, value, ex.getMessage());
            return null;
        }
    }

    private String resolveUserLabel(String pid) {
        try {
            UserSearchDTO user = userService.findInTenantByPid(requireTenant(), pid);
            if (user == null) {
                return null;
            }
            return firstText(user.getDisplayName(), user.getEmail(), user.getPid());
        } catch (RuntimeException ex) {
            log.debug("Skipping user value label resolution for {}: {}", pid, ex.getMessage());
            return null;
        }
    }

    private boolean isReferenceLike(String dataType, String targetEntity) {
        String normalized = dataType == null ? "" : dataType.toLowerCase(Locale.ROOT);
        return "reference".equals(normalized)
                || "user".equals(normalized)
                || "role".equals(normalized)
                || "group".equals(normalized)
                || "department".equals(normalized)
                || StringUtils.hasText(targetEntity);
    }

    private boolean isUserTarget(String targetEntity, String dataType) {
        String normalizedDataType = dataType == null ? "" : dataType.toLowerCase(Locale.ROOT);
        String normalizedTarget = targetEntity == null ? "" : targetEntity.toLowerCase(Locale.ROOT);
        return "user".equals(normalizedDataType)
                || normalizedTarget.contains("user")
                || "sys_user".equals(normalizedTarget)
                || "ab_user".equals(normalizedTarget);
    }

    private String referenceTargetEntity(DecisionFactDTO fact) {
        return firstMapText(
                fact.getReference(),
                "targetEntity",
                "targetModel",
                "targetModelCode",
                "modelCode",
                "refModelCode");
    }

    private String referenceDisplayField(DecisionFactDTO fact) {
        return firstMapText(
                fact.getReference(),
                "displayField",
                "refDisplayField",
                "targetField",
                "fieldCode");
    }

    private String firstMapText(Map<String, ?> source, String... keys) {
        if (source == null || source.isEmpty() || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (!StringUtils.hasText(key)) {
                continue;
            }
            Object value = source.get(key);
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private void putText(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value);
        }
    }

    private record ContextBuildResult(
            DecisionContext context,
            Map<String, Map<String, Object>> resolvedContext,
            List<Map<String, Object>> virtualSourceTrace) {}

    private record EnrichedContext(
            Map<String, Map<String, Object>> context,
            List<Map<String, Object>> virtualSourceTrace) {}

    private record FactMetadataMatch(
            DecisionFactEntityDTO entity,
            DecisionFactDTO fact,
            Set<String> aliases) {}

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
