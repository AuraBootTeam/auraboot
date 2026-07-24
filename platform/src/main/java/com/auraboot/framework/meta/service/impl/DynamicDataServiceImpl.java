package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.meta.security.CsvSafetyUtils;
import com.auraboot.framework.meta.service.DataDomainService;
import com.auraboot.framework.meta.service.FieldMaskService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.base.BaseMetaService;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.dto.FieldMaskRule;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.executor.ExecutorRegistry;
import com.auraboot.framework.meta.service.executor.ModelDataExecutor;
import com.auraboot.framework.meta.ddl.TableMetadataService;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.util.JsonbFieldHelper;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.permission.engine.model.FieldPermissionSet;
import com.auraboot.framework.permission.engine.model.PermissionResult;
import com.auraboot.framework.permission.service.FieldPermissionService;
import com.auraboot.framework.permission.service.PermissionAuditService;
import com.auraboot.framework.permission.service.PermissionFacade;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auraboot.framework.automation.trigger.AutomationTriggerService;
import com.auraboot.framework.meta.constant.SystemFieldConstants;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 动态数据服务实现.
 *
 * <h3>Exception strategy: security-strict, enrichment-tolerant</h3>
 *
 * Two distinct catch(Exception) patterns appear throughout this class. They
 * are intentional and complementary:
 *
 * <ul>
 *   <li><b>§P4 wrap-and-rethrow</b> (security paths): Any failure in
 *       row-level data permission, data domain filter, or field masking
 *       is caught, logged with stack trace, and re-thrown as
 *       {@link MetaServiceException}. <i>The system fails closed</i> —
 *       a permission engine error must never silently degrade access.
 *       Same applies to wrap-as-result for export / import / custom
 *       action which return {@code success=false} carrying the message.</li>
 *
 *   <li><b>§P2 best-effort enrichment</b> (display paths): Avatar URL
 *       resolution, REFERENCE display enrichment, change-log recording,
 *       automation triggers, and field-permission filtering catch + log
 *       but do not throw. The underlying CRUD operation succeeded; the
 *       enrichment is decoration that must never block a write.</li>
 *
 *   <li><b>§P1 per-row tolerance</b> (batch paths): Batch create/update,
 *       sub-table joint save, and relation create/remove iterate N items
 *       and aggregate per-item errors into a result; one bad row must
 *       not abort the rest.</li>
 * </ul>
 *
 * Per {@code docs/standards/core/catch-exception-pattern.md}, all
 * {@code log.warn/error} calls must trail the exception as the final
 * argument so SLF4J emits the stack trace; all {@code throw new
 * MetaServiceException(msg)} must include {@code e} as cause.
 *
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("java/log-injection")
public class DynamicDataServiceImpl extends BaseMetaService implements DynamicDataService {
    private static final String DEFAULT_LIST_SORT_COLUMN = "updated_at";
    private static final String DEFAULT_LIST_SORT_DIRECTION = "DESC";

    private final MetaModelService metadataService;
    private final QueryBuilderService queryBuilderService;
    private final ValidationService validationService;
    private final NamedQueryService namedQueryService;
    private final SecureSqlRewriter secureSqlRewriter;
    private final TypeSystemManager typeSystemManager;
    private final DynamicDataMapper dynamicDataMapper;
    private final SchemaManagementService schemaManagementService;
    private final TableMetadataService tableMetadataService;
    private final ObjectMapper objectMapper;
    private final VirtualFieldEngine virtualFieldEngine;
    private final ChangeTracker changeTracker;
    private final UserMapper userMapper;
    private final FileService fileService;
    private final DataPermissionEngine dataPermissionEngine;
    private final FieldMaskService fieldMaskService;
    private final DataDomainService dataDomainService;
    private final MetaModelMapper metaModelMapper;
    private final ApplicationContext applicationContext;
    private final PayloadTemporalNormalizer payloadTemporalNormalizer;
    private final FieldPermissionService fieldPermissionService;
    private final ExecutorRegistry executorRegistry;
    private static final Set<String> SYSTEM_COLUMNS = SystemFieldConstants.QUERY_TRANSPARENT;

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }

    /**
     * Build a map from field code to display label.
     * Falls back to field code if displayName is null/blank.
     */
    static Map<String, String> buildFieldLabelMap(List<FieldDefinition> fieldDefs) {
        Map<String, String> map = new LinkedHashMap<>();
        if (fieldDefs == null) {
            return map;
        }
        for (FieldDefinition fd : fieldDefs) {
            String label = (fd.getDisplayName() != null && !fd.getDisplayName().isBlank())
                    ? fd.getDisplayName() : fd.getCode();
            map.put(fd.getCode(), label);
        }
        return map;
    }

    // Lazy lookup to break circular dependency: DynamicDataService → AutomationTriggerService → CreateRecordExecutor → DynamicDataService
    private AutomationTriggerService getAutomationTriggerService() {
        return applicationContext.getBean(AutomationTriggerService.class);
    }

    // Lazy lookup (mirrors getAutomationTriggerService) for F3 record-level SLA activation.
    private com.auraboot.framework.bpm.listener.SlaActivationListener getSlaActivationListener() {
        return applicationContext.getBean(com.auraboot.framework.bpm.listener.SlaActivationListener.class);
    }

    private PermissionFacade getPermissionFacade() {
        return applicationContext.getBean(PermissionFacade.class);
    }

    private PermissionAuditService getPermissionAuditService() {
        return applicationContext.getBean(PermissionAuditService.class);
    }

    private TenantMemberService getTenantMemberService() {
        return applicationContext.getBean(TenantMemberService.class);
    }

    @Override
    @Observed(name = "dynamic_data.list", contextualName = "dynamic-data-list")
    public PaginationResult<Map<String, Object>> list(String modelCode, DynamicQueryRequest request) {
        validateModelCode(modelCode);
        logOperation("list", modelCode, request);

        // 获取模型定义
        ModelDefinition model = getModelDefinition(modelCode);

        // Phase 1 virtual-model dispatch: if the model has a non-physical sourceType
        // AND an executor is registered for it, delegate. Otherwise fall through to
        // the existing physical-table inline path (preserves full backward compatibility).
        Optional<ModelDataExecutor> executorOpt = executorRegistry.resolve(model.getSourceType());
        if (executorOpt.isPresent()) {
            return executorOpt.get().list(modelCode, request);
        }

        // VIEW models have no physical table — delegate to NamedQuery
        if ("view".equals(model.getModelType())) {
            return listFromNamedQuery(resolveViewNamedQueryCode(modelCode), request);
        }

        // 构建查询
        QueryBuilderService.QueryBuilder queryBuilder = queryBuilderService.buildConditionQuery(
                model, request.getConditions());

        // Keyset pagination flag — when cursor is present, sort is forced to ORDER BY pid ASC
        boolean useCursor = request.getCursor() != null;

        // 添加排序 (skipped in cursor mode — cursor pagination requires ORDER BY pid ASC)
        if (!useCursor) {
            if (request.getSortFields() != null && !request.getSortFields().isEmpty()) {
                List<SortField> mappedSortFields = mapSortFields(model, request.getSortFields());
                queryBuilder = queryBuilderService.buildOrderQuery(queryBuilder, mappedSortFields, model);
            } else {
                queryBuilder.addOrderBy(DEFAULT_LIST_SORT_COLUMN, DEFAULT_LIST_SORT_DIRECTION);
            }
        }

        // 添加租户条件
        Long tenantId = getCurrentTenantId();
        queryBuilder.addCondition("tenant_id", QueryCondition.Operator.EQ.name(), tenantId);

        String scopedRowFilter = null;
        String scopedDomainFilter = null;
        if (!MetaContext.isDataPermissionBypassed()) {
            // 添加数据权限行级过滤 — fail-secure: exception = deny all
            try {
                Long userId = getCurrentUserId();
                scopedRowFilter = DynamicDataQueryScope.rowFilter(tenantId, modelCode, userId,
                        () -> dataPermissionEngine.buildRowFilter(tenantId, modelCode, userId));
                if (scopedRowFilter != null && !scopedRowFilter.isBlank()) {
                    queryBuilder.addRawCondition(scopedRowFilter);
                }
            } catch (Exception e) {
                // codeql[java/log-injection] Model codes are validated metadata identifiers and are logged as structured parameters only.
                log.error("Failed to apply row-level data permission for model {} — returning empty result for security", logSafe(modelCode), e);
                throw new MetaServiceException("Data permission evaluation failed for model: " + modelCode, e);
            }
        }

        if (!MetaContext.isDataPermissionBypassed()) {
            // Apply data domain isolation filter (D5) — fail-secure
            try {
                Long userId = getCurrentUserId();
                scopedDomainFilter = DynamicDataQueryScope.domainFilter(tenantId, modelCode, userId,
                        () -> dataDomainService.buildDomainFilter(modelCode, userId));
                if (scopedDomainFilter != null && !scopedDomainFilter.isBlank()) {
                    queryBuilder.addRawCondition(scopedDomainFilter);
                }
            } catch (Exception e) {
                // codeql[java/log-injection] Model codes are validated metadata identifiers and are logged as structured parameters only.
                log.error("Failed to apply domain filter for model {} — returning empty result for security", logSafe(modelCode), e);
                throw new MetaServiceException("Data domain filter evaluation failed for model: " + modelCode, e);
            }
        }

        // Add keyword search across searchable fields
        if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
            queryBuilder = queryBuilderService.buildKeywordSearch(queryBuilder, request.getKeyword(), model);
        }

        // Keyset (cursor-based) pagination: when cursor is provided, use WHERE pid > cursor
        // instead of OFFSET for O(1) deep pagination performance.
        if (useCursor) {
            queryBuilder.addCondition("pid", "GT", request.getCursor());
            // Force ORDER BY pid ASC for consistent public cursor traversal
            queryBuilder.addOrderBy("pid", "ASC");
            queryBuilder.setLimit(Math.min(request.getPageSize(), 1000));
        } else {
            // Traditional offset pagination
            PaginationRequest pageRequest = new PaginationRequest(
                    request.getPageNum(),
                    request.getPageSize(),
                    request.getKeyword()
            );
            queryBuilder = queryBuilderService.buildPaginationQuery(queryBuilder, pageRequest);
        }
        
        // 验证查询安全性
        QueryValidationResult validation = queryBuilderService.validateQuery(queryBuilder);
        if (!validation.isValid()) {
            throw new MetaServiceException("Query validation failed: " + validation.getErrorMessage());
        }
        
        // 手动添加租户ID条件到SQL和参数中
        String sql = queryBuilder.getSql();
        Map<String, Object> paramMap = queryBuilder.getParameterMap();
        
        // 执行查询
        List<Map<String, Object>> records = dynamicDataMapper.selectByQuery(sql, paramMap);

        // Read-shape contract: json/jsonb fields leave as JSON strings, never PGobject.
        JsonbFieldHelper.normalizeJsonReadValues(model, records);

        // Build count query with same filters (including row-level data permission)
        QueryBuilderService.QueryBuilder countBuilder = queryBuilderService.buildConditionQuery(
                model, request.getConditions());
        countBuilder.addCondition("tenant_id", QueryCondition.Operator.EQ.name(), tenantId);

        if (!MetaContext.isDataPermissionBypassed()) {
            // Reuse the exact row-level filter from the data query so count cannot drift and
            // the same request does not re-run permission lookup for the count builder.
            if (scopedRowFilter != null && !scopedRowFilter.isBlank()) {
                countBuilder.addRawCondition(scopedRowFilter);
            }
        }

        if (!MetaContext.isDataPermissionBypassed()) {
            // Reuse the exact domain filter from the data query for count consistency and to
            // avoid duplicate domain metadata lookup in one list request.
            if (scopedDomainFilter != null && !scopedDomainFilter.isBlank()) {
                countBuilder.addRawCondition(scopedDomainFilter);
            }
        }

        // Apply the same keyword search to count query for consistency
        if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
            queryBuilderService.buildKeywordSearch(countBuilder, request.getKeyword(), model);
        }

        // Rewrite to count SQL
        String countSql = secureSqlRewriter.rewriteForCount(countBuilder.getSql());
        Map<String, Object> countParamMap = countBuilder.getParameterMap();

        Long total = dynamicDataMapper.countByQuery(countSql, countParamMap);

        if (!MetaContext.isDataPermissionBypassed()) {
            // 应用列级字段脱敏 (policy-based) — fail-secure: masking failure = deny access
            try {
                Long userId = getCurrentUserId();
                List<FieldMaskRule> maskRules = dataPermissionEngine.getFieldMaskRules(tenantId, modelCode, userId);
                if (maskRules != null && !maskRules.isEmpty()) {
                    records = dataPermissionEngine.applyFieldMasking(records, maskRules);
                }
            } catch (Exception e) {
                // codeql[java/log-injection] Model codes are validated metadata identifiers and are logged as structured parameters only.
                log.error("Failed to apply field masking for model {} — returning empty result for security", logSafe(modelCode), e);
                throw new MetaServiceException("Field masking evaluation failed for model: " + modelCode, e);
            }
        }

        if (!MetaContext.isDataPermissionBypassed()) {
            // Apply configurable field masking (A9) — fail-secure
            try {
                Long userId = getCurrentUserId();
                records = fieldMaskService.applyMaskingForList(modelCode, records, userId);
            } catch (Exception e) {
                // codeql[java/log-injection] Model codes are validated metadata identifiers and are logged as structured parameters only.
                log.error("Failed to apply configurable field masking for model {} — returning empty result for security", logSafe(modelCode), e);
                throw new MetaServiceException("Configurable field masking failed for model: " + modelCode, e);
            }
        }

        if (!MetaContext.isDataPermissionBypassed()) {
            // Apply field-level permission filtering — remove hidden fields from results
            records = applyFieldPermissionFilter(modelCode, records);
        }

        records = enrichListRecords(modelCode, records);

        if (useCursor) {
            // Extract nextCursor from the last record's public pid.
            String nextCursor = null;
            if (!records.isEmpty()) {
                Object lastPid = records.get(records.size() - 1).get("pid");
                if (lastPid instanceof String pid && !pid.isBlank()) {
                    nextCursor = pid;
                }
            }
            return PaginationResult.ofCursor(
                    records,
                    total,
                    request.getPageSize(),
                    nextCursor
            );
        }

        return PaginationResult.of(
                records,
                total,
                request.getPageNum(),
                request.getPageSize()
        );
    }

    /**
     * Apply field-level permission filtering to a list of records.
     * Removes keys that are in the hiddenFields set.
     */
    private List<Map<String, Object>> applyFieldPermissionFilter(String modelCode, List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) {
            return records;
        }
        try {
            Long memberId = currentMemberIdForFieldPermissions();
            FieldPermissionSet fieldPerms = fieldPermissionService.getFieldPermissions(memberId, modelCode);
            if (fieldPerms.hiddenFields().isEmpty()) {
                return records;
            }
            Set<String> hidden = fieldPerms.hiddenFields();
            for (Map<String, Object> record : records) {
                hidden.forEach(record::remove);
            }
        } catch (Exception e) {
            // Fail closed for security (matches the sibling row-ACL / field-mask paths in this class):
            // if field-permission evaluation fails we must NOT return records with their hidden fields
            // still present, or hidden field values leak to callers who should not see them.
            // codeql[java/log-injection] Model codes are validated metadata identifiers and are logged as structured parameters only.
            log.error("Failed to apply field permission filter for model {} — failing closed for security", logSafe(modelCode), e);
            throw new MetaServiceException("Field permission evaluation failed for model: " + modelCode, e);
        }
        return records;
    }

    /**
     * Apply field-level permission filtering to a single record.
     */
    private Map<String, Object> applyFieldPermissionFilterSingle(String modelCode, Map<String, Object> record) {
        if (record == null) {
            return record;
        }
        try {
            Long memberId = currentMemberIdForFieldPermissions();
            FieldPermissionSet fieldPerms = fieldPermissionService.getFieldPermissions(memberId, modelCode);
            if (fieldPerms.hiddenFields().isEmpty()) {
                return record;
            }
            List<String> appliedHiddenFields = fieldPerms.hiddenFields().stream()
                    .filter(record::containsKey)
                    .sorted()
                    .toList();
            if (!appliedHiddenFields.isEmpty()) {
                auditHiddenFieldFiltering(modelCode, memberId, record, appliedHiddenFields);
                appliedHiddenFields.forEach(record::remove);
            }
        } catch (Exception e) {
            // Fail closed for security (matches the sibling row-ACL / field-mask paths in this class).
            // codeql[java/log-injection] Model codes are validated metadata identifiers and are logged as structured parameters only.
            log.error("Failed to apply field permission filter for model {} — failing closed for security", logSafe(modelCode), e);
            throw new MetaServiceException("Field permission evaluation failed for model: " + modelCode, e);
        }
        return record;
    }

    private void auditHiddenFieldFiltering(
            String modelCode,
            Long memberId,
            Map<String, Object> record,
            List<String> hiddenFields) {
        if (memberId == null || hiddenFields == null || hiddenFields.isEmpty() || !MetaContext.exists()) {
            return;
        }
        try {
            Long tenantId = MetaContext.getCurrentTenantId();
            getPermissionAuditService().logFieldGovernanceFilter(
                    tenantId,
                    memberId,
                    modelCode,
                    "read",
                    toLongOrNull(record.get("id")),
                    toNonBlankString(record.get("pid")),
                    hiddenFields);
        } catch (Exception e) {
            // Audit is for forensics; the filtered response has already removed
            // hidden fields and must not fail because audit persistence is down.
            log.warn("Failed to submit field-governance audit for model {}: {}",
                    logSafe(modelCode), logSafe(e.getMessage()), e);
        }
    }

    private Long toLongOrNull(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String toNonBlankString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private Long currentMemberIdForFieldPermissions() {
        Long memberId = MetaContext.getCurrentMemberId();
        return memberId != null ? memberId : resolveCurrentTenantMemberId();
    }

    private List<Map<String, Object>> enrichListRecords(String modelCode, List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) {
            return records;
        }

        // Generic REFERENCE field lookup enrichment (GAP-124)
        enrichReferenceDisplayFields(modelCode, records);

        if (!"tenant_member".equals(modelCode)) {
            return records;
        }

        Set<Long> userIds = records.stream()
                .map(record -> asLong(record.get("user_id")))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return records;
        }

        Map<Long, User> userMap = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, user -> user));

        for (Map<String, Object> record : records) {
            Long userId = asLong(record.get("user_id"));
            if (userId == null) {
                continue;
            }
            User user = userMap.get(userId);
            if (user == null) {
                continue;
            }

            String nickName = user.getNickName();
            String username = user.getUserName();
            String displayName = (nickName != null && !nickName.isBlank())
                    ? nickName
                    : ((username != null && !username.isBlank()) ? username : String.valueOf(userId));

            record.put("user_name", displayName);
            record.put("user_nick_name", nickName);
            record.put("user_username", username);
            record.put("user_email", user.getEmail());

            if (user.getImgId() != null && !user.getImgId().isBlank()) {
                try {
                    record.put("user_avatar_url", fileService.getFileDownloadUrl(user.getImgId()));
                } catch (Exception e) {
                    log.warn("Failed to resolve avatar URL for userId={}: {}", logSafe(userId), logSafe(e.getMessage()), e);
                }
            }
        }
        return records;
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Resolve a reference field's target as canonical {@code [targetModelCode, displayField]}, or null.
     * Import normalizes every writing style to {@code refTarget.targetEntity} (C1), so this reads only
     * the canonical key — from the typed refTarget, else extraProps.refTarget, else
     * extraProps.extension.refTarget (the two storage locations the canonical may land in). No
     * compatibility with the legacy modelCode/targetModel writing styles (collapsed at import).
     */
    @SuppressWarnings("unchecked")
    private String[] resolveCanonicalRefTarget(FieldDefinition field) {
        if (field == null) return null;
        if (field.getRefTarget() != null && field.getRefTarget().getTargetEntity() != null
                && !field.getRefTarget().getTargetEntity().isBlank()) {
            return new String[] { field.getRefTarget().getTargetEntity(), field.getRefTarget().getDisplayField() };
        }
        Map<String, Object> extra = field.getExtraProps();
        if (extra == null) return null;
        Object rt = extra.get("refTarget");
        if (!(rt instanceof Map) && extra.get("extension") instanceof Map<?, ?> ext) {
            rt = ((Map<String, Object>) ext).get("refTarget");
        }
        if (!(rt instanceof Map)) return null;
        Map<String, Object> m = (Map<String, Object>) rt;
        String target = m.get("targetEntity") instanceof String s && !s.isBlank() ? s : null;
        if (target == null) return null;
        String display = m.get("displayField") instanceof String d && !d.isBlank() ? d : null;
        return new String[] { target, display };
    }

    /**
     * Resolve the display-name enrichment target for a list field, covering both `reference`
     * fields (via {@link #resolveCanonicalRefTarget}) and renderComponent-driven picker fields
     * whose visual control implies a target: {@code userselect → sys_user},
     * {@code organizationselect → org_department}. Returns {@code {targetModelCode, displayField}}
     * or {@code null} when the field needs no {@code <field>_display} enrichment.
     *
     * <p>{@code memberpicker} is intentionally excluded — it stores a multi-value list, not a
     * single id, so scalar id→name resolution does not apply.
     */
    private String[] resolveEnrichmentTarget(FieldDefinition field) {
        if (field == null) return null;
        String[] canonical = resolveCanonicalRefTarget(field);
        if (canonical != null) return canonical;
        Map<String, Object> extra = field.getExtraProps();
        Object rc = extra == null ? null : extra.get("renderComponent");
        String renderComponent = rc instanceof String s ? s.trim().toLowerCase() : null;
        if (renderComponent == null) return null;
        return switch (renderComponent) {
            case "userselect" -> new String[] { "sys_user", null };
            case "organizationselect" -> new String[] { "org_department", "org_dept_name" };
            default -> null;
        };
    }

    /** True when {@code displayField} on {@code targetModelCode} is masked for this user (sensitive). */
    private boolean isDisplayFieldMasked(Long tenantId, Long userId, String targetModelCode, String displayField) {
        if (tenantId == null || userId == null) return false;
        try {
            List<FieldMaskRule> rules = dataPermissionEngine.getFieldMaskRules(tenantId, targetModelCode, userId);
            if (rules != null) {
                for (FieldMaskRule rule : rules) {
                    if (displayField.equals(rule.getFieldCode())) return true;
                }
            }
        } catch (Exception e) {
            // Fail-safe: if we cannot determine sensitivity, suppress the system-resolved name.
            log.warn("Reference display mask check failed for {}.{}; suppressing enrich",
                    logSafe(targetModelCode), logSafe(displayField));
            return true;
        }
        return false;
    }

    private void enrichReferenceDisplayFields(String modelCode, List<Map<String, Object>> records) {
        Optional<ModelDefinition> modelOpt = metadataService.getModelDefinition(modelCode);
        if (modelOpt.isEmpty()) return;

        ModelDefinition model = modelOpt.get();
        // Enrich `reference` fields AND renderComponent-driven picker fields (userselect /
        // organizationselect) with a resolved `<field>_display` name — see resolveEnrichmentTarget.
        List<FieldDefinition> refFields = model.getFields().stream()
                .filter(f -> resolveEnrichmentTarget(f) != null)
                .toList();

        if (refFields.isEmpty()) return;

        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();

        for (FieldDefinition refField : refFields) {
            String fieldCode = refField.getCode();
            String columnName = refField.getColumnName() != null ? refField.getColumnName() : fieldCode;

            String[] canonical = resolveEnrichmentTarget(refField);
            if (canonical == null) continue;
            String targetModelCode = canonical[0];
            String displayField = canonical[1];
            if (targetModelCode == null || targetModelCode.isBlank()) continue;

            // Sensitive reference: if the display field is masked for THIS user on the target model,
            // do NOT system-resolve a name — leave it to the normal per-user path so masking/field
            // permission is honored (敏感引用不出名/仍按权限). Names (the usual displayField) are not
            // masked, so this only suppresses genuinely sensitive display fields.
            if (displayField != null && isDisplayFieldMasked(tenantId, userId, targetModelCode, displayField)) {
                continue;
            }

            // Collect unique reference IDs
            Set<String> refIds = new java.util.LinkedHashSet<>();
            for (Map<String, Object> record : records) {
                Object val = record.get(columnName);
                if (val != null && !String.valueOf(val).isBlank()) {
                    refIds.add(String.valueOf(val));
                }
            }
            if (refIds.isEmpty()) continue;

            // Batch lookup: query target model for display values
            try {
                Optional<ModelDefinition> targetModelOpt = metadataService.getModelDefinition(targetModelCode);
                String targetTable = targetModelOpt
                        .map(ModelDefinition::getTableName)
                        .orElse(resolveSystemTable(targetModelCode));
                if (targetTable == null) continue;

                String inClause = refIds.stream()
                        .map(id -> "'" + id.replace("'", "''") + "'")
                        .collect(java.util.stream.Collectors.joining(","));

                // Resolve the display column expression + alias (system tables → safe COALESCE).
                String[] displayCol = resolveDisplayColumnExpression(targetModelOpt, targetModelCode, displayField);
                String displayColumnExpr = displayCol[0];
                String displayColumnName = displayCol[1];

                String sql = "SELECT pid, " + displayColumnExpr + " AS " + displayColumnName
                        + " FROM " + targetTable
                        + " WHERE pid IN (" + inClause + ")"
                        + buildSoftDeleteClause(targetModelOpt.orElse(null));

                List<Map<String, Object>> targetRows = dynamicDataMapper.selectByQuery(sql, java.util.Collections.emptyMap());
                Map<String, String> displayMap = new java.util.HashMap<>();
                for (Map<String, Object> row : targetRows) {
                    String pid = String.valueOf(row.get("pid"));
                    Object dispVal = row.get(displayColumnName);
                    if (dispVal != null) {
                        displayMap.put(pid, String.valueOf(dispVal));
                    }
                }

                // Populate _display suffix
                String displayKey = fieldCode + "_display";
                for (Map<String, Object> record : records) {
                    Object val = record.get(columnName);
                    if (val != null) {
                        String display = displayMap.get(String.valueOf(val));
                        if (display != null) {
                            record.put(displayKey, display);
                        }
                    }
                }
            } catch (Exception e) {
                logReferenceEnrichmentFailure(fieldCode, modelCode, e);
            }
        }
    }

    /**
     * Best-effort reference-display enrichment must never silently mask a real error. When the
     * enrichment query fails <b>inside an active transaction</b> it also aborts that transaction
     * (Postgres {@code 25P02}), so the surrounding operation then fails with confusing downstream
     * {@code current transaction is aborted} errors that bury the true cause. Log at ERROR with
     * that correlation so the root cause is a one-line find rather than a stack dig. Outside a
     * transaction the failure is self-contained, so WARN is enough.
     */
    private void logReferenceEnrichmentFailure(String fieldCode, String modelCode, Exception e) {
        // codeql[java/log-injection] Field/model codes are validated metadata identifiers and are logged as structured parameters only.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            log.error("REFERENCE display enrichment for field {} of model {} failed inside an active "
                            + "transaction; this aborts the transaction, so any following 'current "
                            + "transaction is aborted' (25P02) errors are secondary. Root cause: {}",
                    logSafe(fieldCode), logSafe(modelCode), logSafe(e.getMessage()), e);
        } else {
            log.warn("Failed to enrich REFERENCE display for field {} in model {}: {}",
                    logSafe(fieldCode), logSafe(modelCode), logSafe(e.getMessage()), e);
        }
    }

    private static final Map<String, String> SYSTEM_TABLE_MAP = Map.of(
            "ns_user", "ab_user",
            "ab_user", "ab_user",
            // Canonical user model code used across config/frontend (userselect targets,
            // sc_owner_user refTarget) — physically the ab_user table.
            "sys_user", "ab_user"
    );

    private String resolveSystemTable(String modelCode) {
        return SYSTEM_TABLE_MAP.get(modelCode);
    }

    // ==================== Atomic counter ====================

    private static final Set<String> NUMERIC_DATA_TYPES = Set.of(
            "integer", "int", "long", "bigint", "decimal", "numeric", "float", "double");

    /**
     * Resolve a field code to its physical column name, asserting it is numeric.
     * Throws {@link IllegalArgumentException} (NOT {@link MetaServiceException}) so the
     * caller can distinguish a programming error (bad field code) from a runtime model error.
     */
    private String resolveNumericColumn(ModelDefinition model, String fieldCode) {
        if (model.getFields() == null) {
            throw new IllegalArgumentException(
                    "Model " + model.getCode() + " has no fields defined");
        }
        FieldDefinition fd = model.getFields().stream()
                .filter(f -> fieldCode.equals(f.getCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown field '" + fieldCode + "' on model " + model.getCode()));
        String dataType = fd.getDataType();
        if (dataType == null || !NUMERIC_DATA_TYPES.contains(dataType.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "Field '" + fieldCode + "' on model " + model.getCode()
                            + " is not numeric (dataType=" + dataType + ")");
        }
        String col = fd.getColumnName() != null ? fd.getColumnName() : fd.getCode();
        SqlSafetyUtils.validateIdentifier(col, "counter column");
        return col;
    }

    @Override
    @Transactional
    public Optional<Long> incrementWithinCap(String modelCode, String recordId,
                                              String counterCode, long delta, String capCode) {
        assertWritable(modelCode);
        ModelDefinition model = getModelDefinition(modelCode);
        String counterCol = resolveNumericColumn(model, counterCode);
        String capCol = null;
        if (capCode != null) {
            capCol = resolveNumericColumn(model, capCode);
        }
        String softDeleteClause = buildSoftDeleteClause(model);
        FieldDefinition pkField = metadataService.getPrimaryKeyField(modelCode);
        String pkColumn = SqlSafetyUtils.requireIdentifier(
                pkField.getColumnName(), "primary key column");
        long tenantId = getCurrentTenantId();
        Long currentUserId = getCurrentUserId();
        List<Map<String, Object>> rows = dynamicDataMapper.atomicIncrementReturning(
                model.getTableName(), counterCol, capCol, pkColumn,
                softDeleteClause, delta, recordId, tenantId, currentUserId);
        if (rows == null || rows.isEmpty()) {
            return Optional.empty();
        }
        Object val = rows.get(0).get("new_value");
        if (val instanceof Number n) {
            return Optional.of(n.longValue());
        }
        return Optional.empty();
    }

    private String buildSoftDeleteClause(ModelDefinition modelDefinition) {
        if (modelDefinition != null && modelDefinition.isSoftDelete()) {
            return " AND (deleted_flag = FALSE OR deleted_flag IS NULL)";
        }
        return "";
    }

    private String resolveReferenceDisplayColumn(ModelDefinition targetModel, String configuredDisplayField) {
        List<FieldDefinition> fields = targetModel != null && targetModel.getFields() != null
                ? targetModel.getFields()
                : java.util.Collections.emptyList();

        if (configuredDisplayField != null && !configuredDisplayField.isBlank()) {
            for (FieldDefinition field : fields) {
                String columnName = field.getColumnName() != null ? field.getColumnName() : field.getCode();
                if (configuredDisplayField.equals(field.getCode()) || configuredDisplayField.equals(columnName)) {
                    return field.getColumnName() != null ? field.getColumnName() : field.getCode();
                }
            }
        }

        if (targetModel != null) {
            for (FieldDefinition field : metadataService.getDisplayFields(targetModel.getCode())) {
                if (!field.isPrimaryKey()) {
                    return field.getColumnName() != null ? field.getColumnName() : field.getCode();
                }
            }
        }

        for (FieldDefinition field : fields) {
            String columnName = field.getColumnName() != null ? field.getColumnName() : field.getCode();
            String normalized = columnName.toLowerCase(java.util.Locale.ROOT);
            if (normalized.endsWith("_name") || "name".equals(normalized) || normalized.endsWith("_title")
                    || "title".equals(normalized) || normalized.endsWith("_code") || "code".equals(normalized)) {
                return columnName;
            }
        }

        return "pid";
    }

    /**
     * Display column expression mapping for system tables that don't have ModelDefinition registered.
     * Uses COALESCE to fall back through multiple columns (e.g., nick_name → user_name → email).
     * Aliased as 'display_value' in the SELECT clause.
     */
    private static final Map<String, String> SYSTEM_TABLE_DISPLAY_EXPRESSIONS = Map.of(
            "ab_user", "COALESCE(NULLIF(nick_name, ''), NULLIF(user_name, ''), email)",
            "ns_user", "COALESCE(NULLIF(nick_name, ''), NULLIF(user_name, ''), email)",
            "sys_user", "COALESCE(NULLIF(nick_name, ''), NULLIF(user_name, ''), email)"
    );

    /**
     * Choose the display column expression + SELECT alias for a reference-enrichment query,
     * returned as {@code [expression, alias]}.
     *
     * <p>For a <b>system table</b> (no registered {@link ModelDefinition}) we always use the
     * {@link #SYSTEM_TABLE_DISPLAY_EXPRESSIONS} COALESCE expression, <b>regardless of any
     * configured {@code displayField}</b>. System tables have no field metadata to validate a
     * configured display field against, so trusting an arbitrary value would splice it straight
     * into the SQL as a raw column. A plugin writing e.g. {@code refDisplayField: "username"} for
     * a {@code sys_user} reference (whose {@code ab_user} table has {@code nick_name / user_name /
     * email} but no {@code username}) would then produce {@code SELECT pid, username ...} and fail
     * the whole enrichment query with {@code column "username" does not exist} — which, inside a
     * command's {@code bpm:run-rule} contextLookup, aborts the transaction and surfaces as an
     * opaque {@code bpm.rule.execution_failed}. The COALESCE already yields the canonical user
     * display, so ignoring the raw field here is both safe and the intended behaviour.
     */
    private String[] resolveDisplayColumnExpression(
            Optional<ModelDefinition> targetModelOpt, String targetModelCode, String displayField) {
        if (targetModelOpt.isEmpty() && SYSTEM_TABLE_DISPLAY_EXPRESSIONS.containsKey(targetModelCode)) {
            return new String[] { SYSTEM_TABLE_DISPLAY_EXPRESSIONS.get(targetModelCode), "display_value" };
        }
        String col = resolveReferenceDisplayColumn(targetModelOpt.orElse(null), displayField);
        return new String[] { col, col };
    }

    @Override
    public PaginationResult<Map<String, Object>> listByQueryCode(String queryCode, DynamicQueryRequest request) {
        log.info("List by NamedQuery data source: queryCode={}", logSafe(queryCode));
        return listFromNamedQuery(queryCode, request);
    }

    private String resolveViewNamedQueryCode(String modelCode) {
        var modelEntity = metaModelMapper.findCurrentByCode(modelCode);
        if (modelEntity == null) {
            return modelCode;
        }
        Object namedQuery = modelEntity.getExtension() != null
                ? modelEntity.getExtension().get("namedQuery")
                : null;
        if (namedQuery == null) {
            return modelCode;
        }
        String code = namedQuery.toString().trim();
        return code.isEmpty() ? modelCode : code;
    }

    /**
     * List data by delegating to NamedQuery with the given code.
     * Passes through filter conditions and sort fields from the dynamic query request.
     */
    private PaginationResult<Map<String, Object>> listFromNamedQuery(String queryCode, DynamicQueryRequest request) {
        // codeql[java/log-injection] Named query codes are validated metadata identifiers and are logged as structured parameters only.
        log.debug("NamedQuery list: code={}", logSafe(queryCode));
        NamedQueryTestRequest nqRequest = new NamedQueryTestRequest();
        nqRequest.setPage(request.getPageNum());
        nqRequest.setSize(request.getPageSize());
        nqRequest.setExecuteQuery(true);

        ObjectMapper mapper = new ObjectMapper();

        // Pass through filter conditions
        if (request.getConditions() != null && !request.getConditions().isEmpty()) {
            var whereArray = mapper.createArrayNode();
            for (QueryCondition cond : request.getConditions()) {
                var node = mapper.createObjectNode();
                node.put("field", cond.getFieldName());
                node.put("operator", cond.getOperator().name().toLowerCase());
                if (cond.getOperator() == QueryCondition.Operator.IN || cond.getOperator() == QueryCondition.Operator.NOT_IN) {
                    node.set("value", mapper.valueToTree(cond.getValues() != null ? cond.getValues() : List.of()));
                } else if (cond.getOperator() == QueryCondition.Operator.BETWEEN) {
                    node.set("value", mapper.valueToTree(cond.getValues() != null ? cond.getValues() : List.of()));
                } else {
                    node.set("value", mapper.valueToTree(cond.getValue()));
                }
                whereArray.add(node);
            }
            nqRequest.setWhereConditions(whereArray);
        }

        // Pass through sort fields
        if (request.getSortFields() != null && !request.getSortFields().isEmpty()) {
            var orderArray = mapper.createArrayNode();
            for (SortField sf : request.getSortFields()) {
                var node = mapper.createObjectNode();
                node.put("field", sf.getFieldName());
                node.put("direction", sf.getDirection().name());
                orderArray.add(node);
            }
            nqRequest.setOrderConditions(orderArray);
        }

        return namedQueryService.executeQuery(queryCode, nqRequest);
    }

    @Override
    @Observed(name = "dynamic_data.get_by_id", contextualName = "dynamic-data-get-by-id")
    public Map<String, Object> getById(String modelCode, String recordId) {
        validateModelCode(modelCode);
        if (recordId == null || recordId.trim().isEmpty()) {
            throw new MetaServiceException("Record ID cannot be null or empty");
        }
        
        logOperation("getById", modelCode, recordId);

        ModelDefinition model = getModelDefinition(modelCode);

        // Phase 1 virtual-model dispatch: delegate to executor if non-physical sourceType
        // has a registered executor; otherwise fall through to inline physical path.
        Optional<ModelDataExecutor> executorOpt = executorRegistry.resolve(model.getSourceType());
        if (executorOpt.isPresent()) {
            Map<String, Object> record = executorOpt.get().get(modelCode, recordId);
            if (record == null) {
                throw new MetaServiceException("Record not found: " + recordId + " in model: " + modelCode);
            }
            return record;
        }

        FieldDefinition primaryKey = metadataService.getPrimaryKeyField(modelCode);
        Long tenantId = getCurrentTenantId();
        
        // 构建查询条件
        List<QueryCondition> conditions = new ArrayList<>();
        conditions.add(QueryCondition.builder()
                .fieldName(primaryKey.getCode())
                .operator(QueryCondition.Operator.EQ)
                .value(recordId)
                .build());
        conditions.add(QueryCondition.builder()
                .fieldName("tenant_id")
                .operator(QueryCondition.Operator.EQ)
                .value(tenantId)
                .build());
        
        QueryBuilderService.QueryBuilder queryBuilder = queryBuilderService.buildConditionQuery(model, conditions);

        String sql = queryBuilder.getSql();
        Map<String, Object> paramMap = queryBuilder.getParameterMap();
        List<Map<String, Object>> records = dynamicDataMapper.selectByQuery(sql, paramMap);
        
        if (records.isEmpty()) {
            throw new MetaServiceException("Record not found: " + recordId + " in model: " + modelCode);
        }

        Map<String, Object> record = records.get(0);

        // Read-shape contract: json/jsonb fields leave as JSON strings, never PGobject.
        JsonbFieldHelper.normalizeJsonReadValues(model, record);

        if (!MetaContext.isDataPermissionBypassed()) {
            // Apply the Rule Center-backed permission pipeline before the
            // legacy row-level gate. A Rule Center DENY is responsible for
            // producing the permission audit/trace row; if the legacy row ACL
            // runs first, the request can fail with no explainability trail.
            // Field-level masking still happens afterwards so the evaluator
            // sees the original record shape.
            try {
                enforceRuleCenterRecordPermission(modelCode, recordId, record);
            } catch (MetaServiceException e) {
                throw e;
            } catch (Exception e) {
                log.error("Failed to evaluate Rule Center permission for model {} record {} — failing closed for security",
                        logSafe(modelCode), logSafe(recordId), e);
                throw new MetaServiceException(
                        "Rule Center permission evaluation failed for model: " + modelCode, e);
            }
        }

        if (!MetaContext.isDataPermissionBypassed()) {
            // Apply row-level access check for single record — fail-secure: any
            // non-MetaServiceException must surface as a 5xx, not be swallowed.
            // Mirrors the list() pattern at lines 173 and 185. This remains an
            // additional legacy guard after the Rule Center permission pipeline
            // has either allowed the record or produced a DENY trace.
            try {
                Long userId = getCurrentUserId();
                if (!dataPermissionEngine.canAccessRecord(tenantId, modelCode, userId, record)) {
                    throw new MetaServiceException("Access denied: you do not have permission to view this record");
                }
            } catch (MetaServiceException e) {
                throw e;
            } catch (Exception e) {
                log.error("Failed to evaluate row-level access for model {} record {} — failing closed for security",
                        logSafe(modelCode), logSafe(recordId), e);
                throw new MetaServiceException(
                        "Data permission evaluation failed for model: " + modelCode, e);
            }
        }

        if (!MetaContext.isDataPermissionBypassed()) {
            // Apply column-level field masking (policy-based) — fail-secure.
            // Returning the unmasked record on internal error would leak the
            // very fields the policy is configured to hide.
            try {
                Long userId = getCurrentUserId();
                List<FieldMaskRule> maskRules = dataPermissionEngine.getFieldMaskRules(tenantId, modelCode, userId);
                if (maskRules != null && !maskRules.isEmpty()) {
                    List<Map<String, Object>> masked = dataPermissionEngine.applyFieldMasking(List.of(record), maskRules);
                    if (masked != null && !masked.isEmpty()) {
                        record = masked.get(0);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to apply field masking for model {} record {} — failing closed for security",
                        logSafe(modelCode), logSafe(recordId), e);
                throw new MetaServiceException(
                        "Field masking evaluation failed for model: " + modelCode, e);
            }
        }

        if (!MetaContext.isDataPermissionBypassed()) {
            // Apply configurable field masking for detail view (A9) — fail-secure.
            try {
                Long userId = getCurrentUserId();
                record = fieldMaskService.applyMaskingForDetail(modelCode, record, userId);
            } catch (Exception e) {
                log.error("Failed to apply configurable field masking for model {} record {} — failing closed for security",
                        logSafe(modelCode), logSafe(recordId), e);
                throw new MetaServiceException(
                        "Detail-view field masking failed for model: " + modelCode, e);
            }
        }

        if (!MetaContext.isDataPermissionBypassed()) {
            // Apply field-level permission filtering — remove hidden fields
            record = applyFieldPermissionFilterSingle(modelCode, record);
        }

        // Resolve reference display names (same as list) so the detail page shows names, not pids.
        List<Map<String, Object>> single = new java.util.ArrayList<>(1);
        single.add(record);
        enrichReferenceDisplayFields(modelCode, single);
        record = single.get(0);

        return record;
    }

    private void enforceRuleCenterRecordPermission(String modelCode, String recordId, Map<String, Object> record) {
        Long subjectId = currentMemberIdForRuleCenterPermission();
        if (subjectId == null) {
            throw new MetaServiceException("Permission context missing for model: " + modelCode);
        }

        PermissionFacade facade = getPermissionFacade();
        if (facade == null) {
            throw new MetaServiceException("Permission facade unavailable for model: " + modelCode);
        }

        PermissionResult result = facade.canOperate(subjectId, modelCode, "read", record);
        if (!result.granted()) {
            throw new MetaServiceException("Access denied: you do not have permission to view this record");
        }
    }

    private Long currentMemberIdForRuleCenterPermission() {
        Long memberId = MetaContext.getCurrentMemberId();
        return memberId != null ? memberId : resolveCurrentTenantMemberId();
    }

    private Long resolveCurrentTenantMemberId() {
        if (!MetaContext.exists()) {
            return null;
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        if (tenantId == null || userId == null) {
            return null;
        }
        TenantMember member = getTenantMemberService().findByTenantIdAndUserId(tenantId, userId);
        return member == null ? null : member.getId();
    }

    @Override
    @Transactional
    public Map<String, Object> create(String modelCode, Map<String, Object> data) {
        validateModelCode(modelCode);
        assertWritable(modelCode);
        if (data == null || data.isEmpty()) {
            throw new MetaServiceException("Data cannot be null or empty");
        }

        // Field-level write permission (gap #1): strip fields the current user may not write
        stripNonWritableFields(modelCode, data);

        logOperation("create", modelCode, data.keySet());

        ModelDefinition model = getModelDefinition(modelCode);
        
        // 检查表是否存在，如果不存在则自动创建
        ensureTableExists(modelCode);
        
        // Normalize temporal string values to typed objects (LocalDate/Instant) before validation
        payloadTemporalNormalizer.normalize(data, model);
        // 使用验证服务的严格模式进行验证
        // 验证失败会抛出异常并触发事务回滚
        validationService.validateAndThrow(model, data, ValidationContext.CREATE);
        
        // 设置系统字段
        Map<String, Object> enrichedData = new HashMap<>(data);
        enrichedData.put("created_at", java.time.Instant.now());
        enrichedData.put("created_by", getCurrentUserId());
        enrichedData.put("updated_at", java.time.Instant.now());
        enrichedData.put("updated_by", getCurrentUserId());
        enrichedData.put("tenant_id", getCurrentTenantId());
        // Same reason as the fields above: a derived row created under a command authorized for one
        // aggregate belongs to that aggregate, not to whichever one the payload names.
        injectAggregateBinding(model, enrichedData);
        
        // 生成主键（如果需要）
        FieldDefinition primaryKey = metadataService.getPrimaryKeyField(modelCode);
        if (!enrichedData.containsKey(primaryKey.getCode())) {
            // 使用TypeSystemManager根据字段类型生成主键
            Object generatedPk = typeSystemManager.generatePrimaryKey(primaryKey);
            enrichedData.put(primaryKey.getCode(), generatedPk);
            log.debug("Generated primary key for model {}: {} = {}", 
                     logSafe(modelCode), logSafe(primaryKey.getCode()), logSafe(generatedPk));
        }
        
        // 数据类型转换
        enrichedData = convertDataTypes(model, enrichedData);

        // Filter out non-writable virtual fields (COMPUTED_READONLY, TRANSIENT)
        List<String> changedFields = new ArrayList<>(enrichedData.keySet());
        filterVirtualFields(model, enrichedData);

        Map<String, Object> columnData = toColumnData(model, enrichedData);

        // Identify JSONB host columns for SQL generation
        Set<String> jsonbColumns = JsonbFieldHelper.getJsonbHostColumns(model);

        // 执行插入
        int result = jsonbColumns.isEmpty()
                ? dynamicDataMapper.insert(model.getTableName(), columnData)
                : dynamicDataMapper.insertWithJsonb(model.getTableName(), columnData, jsonbColumns);
        if (result <= 0) {
            throw new MetaServiceException("Failed to create record");
        }

        // Materialize computed fields after insert
        String recordIdValue = enrichedData.get(primaryKey.getCode()).toString();
        virtualFieldEngine.materialize(modelCode, recordIdValue, changedFields);

        // Record change log.
        //
        // This read-back is the platform reading the row it just wrote, not the
        // caller reading data: the record feeds the change-log snapshot, the
        // automation trigger payload and the SLA activation payload. Projecting
        // it through the caller's read permissions would (a) fail the whole
        // create for a caller that may create but not read the model, and
        // (b) hand automations and SLA a field-masked, incomplete record.
        // The create itself is already authorized by the caller-facing layer.
        Map<String, Object> createdRecord =
                MetaContext.runWithoutDataPermission(() -> getById(modelCode, recordIdValue));
        try {
            List<FieldChange> changes = changeTracker.diff(null, createdRecord, modelCode);
            changeTracker.recordChange(ChangeRecord.builder()
                    .modelCode(modelCode)
                    .recordId(recordIdValue)
                    .operation("create")
                    .changedBy(getCurrentUserId())
                    .changes(changes)
                    .snapshotAfter(createdRecord)
                    .build());
        } catch (Exception e) {
            log.error("Failed to record change log for create: model={}, id={}: {}",
                    logSafe(modelCode), logSafe(recordIdValue), logSafe(e.getMessage()), e);
        }

        // Trigger automations for record creation
        try {
            getAutomationTriggerService().onRecordCreate(modelCode, recordIdValue, createdRecord);
        } catch (Exception e) {
            log.error("Failed to trigger automations for create: model={}, id={}: {}",
                    logSafe(modelCode), logSafe(recordIdValue), logSafe(e.getMessage()), e);
        }

        // F3: activate record-level SLA (targetType=RECORD) for this model, if any.
        try {
            getSlaActivationListener().onRecordCreate(modelCode, recordIdValue, createdRecord);
        } catch (Exception e) {
            log.error("Failed to activate record-level SLA for create: model={}, id={}: {}",
                    logSafe(modelCode), logSafe(recordIdValue), logSafe(e.getMessage()), e);
        }

        return createdRecord;
    }

    /**
     * 数据类型转换
     */
    private Map<String, Object> convertDataTypes(ModelDefinition model, Map<String, Object> data) {
        Map<String, Object> convertedData = new HashMap<>(data);
        
        for (FieldDefinition field : model.getFields()) {
            // Skip JSONB virtual fields — they are merged and serialized separately
            if (field.isJsonbVirtual()) continue;

            String fieldCode = field.getCode();
            Object value = convertedData.get(fieldCode);

            if (value == null) {
                continue;
            }

            try {
                Object convertedValue = convertFieldValue(field, value);
                convertedData.put(fieldCode, convertedValue);
            } catch (Exception e) {
                log.warn("Failed to convert field {} value {}: {}",
                        logSafe(fieldCode), logSafe(value), logSafe(e.getMessage()), e);
                // 保持原值，让数据库处理类型转换
            }
        }
        
        return convertedData;
    }

    /**
     * Filter out non-writable virtual fields (COMPUTED_READONLY, TRANSIENT) from data map
     */
    private void filterVirtualFields(ModelDefinition model, Map<String, Object> data) {
        if (model.getFields() == null) {
            return;
        }
        Set<String> virtualFieldCodes = model.getFields().stream()
                .filter(f -> f.isComputedReadonly() || f.isTransientField())
                .map(FieldDefinition::getCode)
                .collect(Collectors.toSet());

        data.keySet().removeAll(virtualFieldCodes);
    }

    /**
     * Field-level write permission enforcement (gap #1).
     *
     * <p>Removes from the incoming payload any field the current user is not
     * permitted to write (effective {@code field_write} data-permission policies).
     * Stripping (rather than rejecting) keeps clients tolerant while guaranteeing
     * restricted fields never reach the row; each strip is logged for audit.
     */
    /**
     * Remove fields the caller may not write.
     *
     * @return the field codes actually removed, so a caller holding the stored row can tell an
     *         apologetic full-row round-trip from a real attempt to change a forbidden field.
     */
    private Set<String> stripNonWritableFields(String modelCode, Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return Collections.emptySet();
        }
        if (MetaContext.isDataPermissionBypassed()) {
            return Collections.emptySet();
        }
        Long tenantId = getCurrentTenantId();
        Long userId = getCurrentUserId();
        Set<String> nonWritable = dataPermissionEngine.getNonWritableFields(tenantId, modelCode, userId);
        if (nonWritable == null || nonWritable.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> stripped = new LinkedHashSet<>();
        for (String fieldCode : nonWritable) {
            if (data.remove(fieldCode) != null) {
                stripped.add(fieldCode);
                log.warn("Field-write permission: stripped non-writable field '{}' from {} write by user {}",
                        logSafe(fieldCode), logSafe(modelCode), logSafe(userId));
            }
        }
        return stripped;
    }

    /**
     * Turn a silently-dropped write into a visible refusal when the caller actually tried to
     * change something they may not change.
     *
     * <p>Silently stripping is the friendly behaviour for the common "read a row, edit two
     * fields, send the whole thing back" client: it submitted the forbidden field, but it
     * submitted the value that is already stored, so nothing was denied and nothing should be
     * reported. A submitted value that <em>differs</em> from the stored one is a different
     * event — the caller asked for a change and did not get it — and letting that pass in
     * silence is how a permission problem becomes an invisible one.</p>
     */
    // package-private + static: the visibility property (a real refusal is never silent, an
    // unchanged round-trip never complains) is directly tested; no instance state is involved.
    static void assertNoDeniedFieldWrites(String modelCode, Map<String, Object> submitted,
                                          Set<String> strippedFields, Map<String, Object> existingRecord) {
        if (strippedFields == null || strippedFields.isEmpty() || submitted == null || existingRecord == null) {
            return;
        }
        List<String> denied = new ArrayList<>();
        for (String fieldCode : strippedFields) {
            if (!submitted.containsKey(fieldCode)) {
                continue;
            }
            if (ValidationServiceImpl.valueChanges(existingRecord.get(fieldCode), submitted.get(fieldCode))) {
                denied.add(fieldCode);
            }
        }
        if (!denied.isEmpty()) {
            throw new MetaServiceException("FIELD_WRITE_DENIED: not permitted to change "
                    + String.join(", ", denied) + " on " + modelCode);
        }
    }

    /**
     * 转换单个字段值
     */
    private Object convertFieldValue(FieldDefinition field, Object value) {
        if (value == null) {
            return null;
        }
        
        String dataType = field.getDataType();
        if (dataType == null) {
            return value;
        }
        
        switch (dataType.toUpperCase()) {
            case "DATE":
                if (value instanceof String) {
                    try {
                        return java.sql.Date.valueOf((String) value);
                    } catch (Exception e) {
                        throw new MetaServiceException(
                            "Invalid date value for field '" + field.getCode() + "': " + value, e);
                    }
                }
                return value;

            case "DATETIME":
            case "TIMESTAMP":
            case "LOCALDATETIME":
                if (value instanceof java.time.Instant instant) {
                    return java.sql.Timestamp.from(instant);
                }
                if (value instanceof java.time.LocalDateTime localDateTime) {
                    return java.sql.Timestamp.valueOf(localDateTime);
                }
                if (value instanceof String) {
                    try {
                        return java.sql.Timestamp.valueOf((String) value);
                    } catch (Exception e) {
                        throw new MetaServiceException(
                            "Invalid datetime value for field '" + field.getCode() + "': " + value, e);
                    }
                }
                return value;

            case "INTEGER":
                if (value instanceof String) {
                    try {
                        return Integer.valueOf((String) value);
                    } catch (NumberFormatException e) {
                        throw new MetaServiceException(
                            "Invalid integer value for field '" + field.getCode() + "': " + value);
                    }
                }
                return value;

            case "LONG":
                if (value instanceof String) {
                    try {
                        return Long.valueOf((String) value);
                    } catch (NumberFormatException e) {
                        throw new MetaServiceException(
                            "Invalid long value for field '" + field.getCode() + "': " + value);
                    }
                }
                return value;

            case "DECIMAL":
                if (value instanceof String) {
                    try {
                        return new java.math.BigDecimal((String) value);
                    } catch (NumberFormatException e) {
                        throw new MetaServiceException(
                            "Invalid decimal value for field '" + field.getCode() + "': " + value);
                    }
                }
                return value;
                
            case "BOOLEAN":
                if (value instanceof String) {
                    return Boolean.valueOf((String) value);
                }
                return value;
                
            default:
                return value;
        }
    }

    /**
     * 确保模型对应的表存在，如果不存在则自动创建
     */
    private void ensureTableExists(String modelCode) {

            ModelDefinition model = getModelDefinition(modelCode);
            String tableName = model.getTableName();
            
            // 检查表是否存在
            if (!tableMetadataService.tableExists(tableName)) {
                log.info("Table {} does not exist, creating it automatically for model: {}",
                        logSafe(tableName), logSafe(modelCode));
                
                // 使用SchemaManagementService创建表
                SchemaOperationResult result = schemaManagementService.createTableByModel(modelCode);
                if (!result.isSuccess()) {
                    throw new MetaServiceException("Failed to create table for model " + modelCode + ": " + result.getMessage());
                }
                
                log.info("Successfully created table {} for model: {}", logSafe(tableName), logSafe(modelCode));
            }

    }

    @Override
    @Transactional
    public Map<String, Object> update(String modelCode, String recordId, Map<String, Object> inputData) {
        validateModelCode(modelCode);
        assertWritable(modelCode);
        if (recordId == null || recordId.trim().isEmpty()) {
            throw new MetaServiceException("Record ID cannot be null or empty");
        }
        if (inputData == null || inputData.isEmpty()) {
            throw new MetaServiceException("Data cannot be null or empty");
        }
        // Work on a copy. payloadTemporalNormalizer.normalize() rewrites temporal values in
        // place, so an immutable argument — Map.of(...), which is the natural thing to write
        // for a small update — throws a message-less UnsupportedOperationException from deep
        // inside, and only when the payload happens to carry a date/time field. Copying also
        // means we no longer mutate a caller's map behind its back.
        Map<String, Object> data = new LinkedHashMap<>(inputData);
        
        // Field-level write permission (gap #1): strip fields the current user may not write
        Set<String> strippedNonWritable = stripNonWritableFields(modelCode, data);

        logOperation("update", modelCode, recordId, data.keySet());
        
        try {
            ModelDefinition model = getModelDefinition(modelCode);
            
            // Check if record exists
            Map<String, Object> existingRecord = getById(modelCode, recordId);
            if (existingRecord == null) {
                throw new MetaServiceException("Record not found with ID: " + recordId);
            }

            // A field the caller may not write was dropped above. Now that the stored row is in
            // hand we can tell whether that was harmless (they sent back the value already there)
            // or a real refusal that must not be delivered silently.
            assertNoDeniedFieldWrites(modelCode, inputData, strippedNonWritable, existingRecord);


            // Normalize temporal string values to typed objects (LocalDate/Instant) before validation
            payloadTemporalNormalizer.normalize(data, model);
            // 使用验证服务的严格模式进行验证
            // 验证失败会抛出异常并触发事务回滚
            validationService.validateAndThrow(model, data, ValidationContext.UPDATE);
            // Field-level domain invariants (immutable / immutableWhen). These are decided
            // against the row as it currently stands, which is why they need existingRecord
            // and cannot live in the payload-only validation above. They are invariants, not
            // permissions: admin and system handlers are bound by them, and a command that
            // inherited an aggregate's authority does not get to waive them.
            validationService.validateImmutabilityAndThrow(model, data, existingRecord);
            // Validation intentionally works with java.time domain types. Convert
            // them to JDBC-native values only afterwards, matching the CREATE
            // path and preventing MyBatis from binding Instant as an untyped
            // Object for dynamic timestamp columns.
            data = convertDataTypes(model, data);
            
            // Set system fields
            Map<String, Object> enrichedData = new HashMap<>(data);
            enrichedData.put("updated_at", java.sql.Timestamp.from(java.time.Instant.now()));
            enrichedData.put("updated_by", getCurrentUserId());
            enrichedData.remove("tenant_id");
            enrichedData.remove("created_at");
            enrichedData.remove("created_by");
            
            // Remove primary key field (not allowed to update)
            try {
                FieldDefinition primaryKey = metadataService.getPrimaryKeyField(modelCode);
                if (primaryKey != null) {
                    enrichedData.remove(primaryKey.getCode());
                }
            } catch (Exception e) {
                log.warn("Could not get primary key field for model {}: {}", logSafe(modelCode), logSafe(e.getMessage()), e);
                // Try common primary key field names
                enrichedData.remove("id");
                enrichedData.remove(modelCode + "_id");
                enrichedData.remove("device_id"); // For device model specifically
            }
            
            // Filter out non-writable virtual fields (COMPUTED_READONLY, TRANSIENT)
            List<String> changedFields = new ArrayList<>(enrichedData.keySet());
            filterVirtualFields(model, enrichedData);

            // Optimistic locking: extract expectedVersion if present
            Object expectedVersion = enrichedData.remove("_expectedVersion");

            // Use JSONB-merge-aware toColumnData for UPDATE to preserve unmodified JSONB keys
            Map<String, Object> columnData = toColumnDataForUpdate(model, enrichedData, existingRecord);
            FieldDefinition primaryKey = metadataService.getPrimaryKeyField(modelCode);
            String primaryKeyColumn = primaryKey.getColumnName() != null
                    ? primaryKey.getColumnName()
                    : primaryKey.getCode();

            // If expectedVersion provided, add optimistic lock condition
            if (expectedVersion != null) {
                columnData.put("row_version", ((Number) expectedVersion).intValue() + 1);
            }

            // Execute update with tenant and DataScope guards in the write SQL itself.
            Set<String> jsonbColumns = JsonbFieldHelper.getJsonbHostColumns(model);
            int result = executeScopedUpdate(
                    model, modelCode, primaryKeyColumn, recordId, columnData, jsonbColumns, expectedVersion);
            if (result <= 0) {
                if (expectedVersion != null) {
                    throw new MetaServiceException("Update failed: version conflict (expected version " + expectedVersion + ")");
                }
                throw new MetaServiceException("Failed to update record");
            }

            // Materialize computed fields after update
            virtualFieldEngine.materialize(modelCode, recordId, changedFields);

            // Record change log. Same platform-internal read-back as create():
            // the caller's read permissions must not gate the row we just wrote.
            // The pre-update read of existingRecord above deliberately keeps the
            // permission projection — you may not modify a record you cannot see.
            Map<String, Object> updatedRecord =
                    MetaContext.runWithoutDataPermission(() -> getById(modelCode, recordId));
            try {
                List<FieldChange> changes = changeTracker.diff(existingRecord, updatedRecord, modelCode);
                if (!changes.isEmpty()) {
                    changeTracker.recordChange(ChangeRecord.builder()
                            .modelCode(modelCode)
                            .recordId(recordId)
                            .operation("update")
                            .changedBy(getCurrentUserId())
                            .changes(changes)
                            .snapshotBefore(existingRecord)
                            .snapshotAfter(updatedRecord)
                            .build());
                }
            } catch (Exception e) {
                log.error("Failed to record change log for update: model={}, id={}: {}",
                        logSafe(modelCode), logSafe(recordId), logSafe(e.getMessage()), e);
            }

            // Trigger automations for record update
            try {
                getAutomationTriggerService().onRecordUpdate(modelCode, recordId, existingRecord, updatedRecord);
            } catch (Exception e) {
                log.error("Failed to trigger automations for update: model={}, id={}: {}",
                        logSafe(modelCode), logSafe(recordId), logSafe(e.getMessage()), e);
            }

            return updatedRecord;
            
        } catch (Exception e) {
            log.error("Update operation failed for model {} with ID {}: {}",
                    logSafe(modelCode), logSafe(recordId), logSafe(e.getMessage()), e);
            throw new MetaServiceException("Update failed: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public void delete(String modelCode, String recordId) {
        validateModelCode(modelCode);
        assertWritable(modelCode);
        if (recordId == null || recordId.trim().isEmpty()) {
            throw new MetaServiceException("Record ID cannot be null or empty");
        }

        logOperation("delete", modelCode, recordId);

        ModelDefinition model = getModelDefinition(modelCode);

        // Get record before deletion for change tracking
        Map<String, Object> existingRecord = getById(modelCode, recordId);

        // 构建删除条件
        FieldDefinition primaryKey = metadataService.getPrimaryKeyField(modelCode);
        String primaryKeyColumn = primaryKey.getColumnName() != null
                ? primaryKey.getColumnName()
                : primaryKey.getCode();

        int result;
        if (model.isSoftDelete()) {
            // Soft delete: UPDATE deleted_flag = true
            Map<String, Object> updateData = new java.util.HashMap<>();
            updateData.put("deleted_flag", true);
            updateData.put("updated_at", java.time.Instant.now());
            updateData.put("updated_by", getCurrentUserId());
            result = executeScopedUpdate(model, modelCode, primaryKeyColumn, recordId, updateData, Set.of(), null);
        } else {
            // Hard delete: DELETE FROM (default behavior)
            result = executeScopedDelete(model, modelCode, primaryKeyColumn, recordId);
        }
        if (result <= 0) {
            throw new MetaServiceException("Failed to delete record");
        }

        // Record change log
        try {
            List<FieldChange> changes = changeTracker.diff(existingRecord, null, modelCode);
            changeTracker.recordChange(ChangeRecord.builder()
                    .modelCode(modelCode)
                    .recordId(recordId)
                    .operation("delete")
                    .changedBy(getCurrentUserId())
                    .changes(changes)
                    .snapshotBefore(existingRecord)
                    .build());
        } catch (Exception e) {
            log.error("Failed to record change log for delete: model={}, id={}: {}",
                    logSafe(modelCode), logSafe(recordId), logSafe(e.getMessage()), e);
        }
    }

    private int executeScopedUpdate(
            ModelDefinition model,
            String modelCode,
            String primaryKeyColumn,
            String recordId,
            Map<String, Object> columnData,
            Set<String> jsonbColumns,
            Object expectedVersion) {
        if (columnData == null || columnData.isEmpty()) {
            throw new MetaServiceException("Update data cannot be empty");
        }

        String tableName = SqlSafetyUtils.requireIdentifier(model.getTableName(), "table name");
        String pkColumn = SqlSafetyUtils.requireIdentifier(primaryKeyColumn, "primary key column");
        Long tenantId = getCurrentTenantId();
        Long userId = getCurrentUserId();

        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("UPDATE ")
                .append(tableName)
                .append(" SET ");
        int index = 0;
        for (Map.Entry<String, Object> entry : columnData.entrySet()) {
            String columnName = SqlSafetyUtils.requireIdentifier(entry.getKey(), "column name");
            if (index > 0) {
                sql.append(", ");
            }
            String paramName = "set" + index;
            if (jsonbColumns != null && jsonbColumns.contains(columnName)) {
                sql.append(columnName).append(" = #{params.").append(paramName)
                        .append(",jdbcType=OTHER,typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler}::jsonb");
            } else {
                sql.append(columnName).append(" = #{params.").append(paramName).append("}");
            }
            Object parameterValue = entry.getValue();
            // The SQL cast alone is not enough: MyBatis sees a Map first and
            // asks PostgreSQL for its hstore handler. Serialize every structured
            // JSONB value at this final binding chokepoint so regular JSON fields
            // and values produced by virtual-field merging behave identically.
            if (jsonbColumns != null && jsonbColumns.contains(columnName)
                    && parameterValue != null && !(parameterValue instanceof String)) {
                parameterValue = JsonbFieldHelper.toJsonString(parameterValue);
            }
            params.put(paramName, parameterValue);
            index++;
        }

        params.put("recordId", recordId);
        params.put("tenantId", tenantId);
        sql.append(" WHERE ")
                .append(pkColumn)
                .append(" = #{params.recordId}")
                .append(" AND tenant_id = #{params.tenantId}");
        if (expectedVersion != null) {
            params.put("expectedVersion", expectedVersion);
            sql.append(" AND row_version = #{params.expectedVersion}");
        }
        appendAggregateBindingGuard(sql, params, model);
        appendScopedWriteGuards(sql, tenantId, modelCode, userId, "update");

        return dynamicDataMapper.updateByQuery(sql.toString(), params);
    }

    private int executeScopedDelete(
            ModelDefinition model,
            String modelCode,
            String primaryKeyColumn,
            String recordId) {
        String tableName = SqlSafetyUtils.requireIdentifier(model.getTableName(), "table name");
        String pkColumn = SqlSafetyUtils.requireIdentifier(primaryKeyColumn, "primary key column");
        Long tenantId = getCurrentTenantId();
        Long userId = getCurrentUserId();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("recordId", recordId);
        params.put("tenantId", tenantId);

        StringBuilder sql = new StringBuilder("DELETE FROM ")
                .append(tableName)
                .append(" WHERE ")
                .append(pkColumn)
                .append(" = #{params.recordId}")
                .append(" AND tenant_id = #{params.tenantId}");
        appendAggregateBindingGuard(sql, params, model);
        appendScopedWriteGuards(sql, tenantId, modelCode, userId, "delete");

        return dynamicDataMapper.deleteByQuery(sql.toString(), params);
    }

    /**
     * Pin a write to the aggregate root the command was authorized against.
     *
     * <p>Deliberately NOT gated on {@link MetaContext#isDataPermissionBypassed()}. That flag means
     * "do not re-run the caller's read projection", which is a statement about re-deciding policy.
     * This is not a decision — no policy is consulted — it is the execution of a boundary the entry
     * already fixed. A command authorized for Q1001 must not reach Q2002's rows precisely on the
     * paths that inherit its authority, so switching it off there would remove the guard exactly
     * where it is load-bearing.</p>
     *
     * <p>Inert unless both an aggregate scope is open and the model declares a binding, so models
     * opt in one at a time rather than the whole platform changing behaviour at once.</p>
     */
    // package-private + static: the safety property (an open aggregate scope pins every guarded
    // write, including on bypassed paths) is directly tested, and the guard depends on no
    // instance state.
    static void appendAggregateBindingGuard(StringBuilder sql, Map<String, Object> params, ModelDefinition model) {
        String aggregateId = MetaContext.getCommandAggregateId();
        if (aggregateId == null || model == null) {
            return;
        }
        ModelDefinition.AggregateBinding binding = model.getAggregateBinding();
        if (binding == null || binding.getLocalField() == null || binding.getLocalField().isBlank()) {
            return;
        }
        String column = resolveBindingColumn(model, binding.getLocalField());
        params.put("authorizedAggregateId", aggregateId);
        sql.append(" AND ").append(column).append(" = #{params.authorizedAggregateId}");
    }

    /**
     * A binding names a <em>field code</em>; SQL needs the physical column. Falls back to the code
     * when the model declares no explicit column, which is the common case.
     */
    private static String resolveBindingColumn(ModelDefinition model, String fieldCode) {
        String column = fieldCode;
        if (model.getFields() != null) {
            for (FieldDefinition field : model.getFields()) {
                if (fieldCode.equals(field.getCode()) && field.getColumnName() != null
                        && !field.getColumnName().isBlank()) {
                    column = field.getColumnName();
                    break;
                }
            }
        }
        return SqlSafetyUtils.requireIdentifier(column, "aggregate binding column");
    }

    /**
     * Stamp a newly created row with the aggregate the entry authorized.
     *
     * <p>Injected for exactly the reason {@code tenant_id} and {@code created_by} are injected a
     * few lines above: the client does not get to choose. A derived row created while a command is
     * authorized for Q1001 belongs to Q1001, whatever the payload claims — otherwise a caller could
     * plant rows under another document and reach them later through a legitimate scope.</p>
     */
    // package-private + static: directly tested, no instance state involved.
    static void injectAggregateBinding(ModelDefinition model, Map<String, Object> data) {
        String aggregateId = MetaContext.getCommandAggregateId();
        if (aggregateId == null || model == null || data == null) {
            return;
        }
        ModelDefinition.AggregateBinding binding = model.getAggregateBinding();
        if (binding == null || binding.getLocalField() == null || binding.getLocalField().isBlank()) {
            return;
        }
        data.put(binding.getLocalField(), aggregateId);
    }

    private void appendScopedWriteGuards(
            StringBuilder sql,
            Long tenantId,
            String modelCode,
            Long userId,
            String operation) {
        if (MetaContext.isDataPermissionBypassed()) {
            return;
        }

        try {
            String rowFilter = DynamicDataQueryScope.rowFilter(tenantId, modelCode, userId,
                    () -> dataPermissionEngine.buildRowFilter(tenantId, modelCode, userId));
            appendScopedBulkFilter(sql, rowFilter);
        } catch (Exception e) {
            log.error("Failed to apply row-level data permission for {} on model {} — denying access",
                    operation, logSafe(modelCode), e);
            throw new MetaServiceException("Data permission evaluation failed for model: " + modelCode, e);
        }

        try {
            String domainFilter = DynamicDataQueryScope.domainFilter(tenantId, modelCode, userId,
                    () -> dataDomainService.buildDomainFilter(modelCode, userId));
            appendScopedBulkFilter(sql, domainFilter);
        } catch (Exception e) {
            log.error("Failed to apply domain filter for {} on model {} — denying access",
                    operation, logSafe(modelCode), e);
            throw new MetaServiceException("Data domain filter evaluation failed for model: " + modelCode, e);
        }
    }

    @Override
    public DynamicBatchResponse batchCreate(String modelCode, List<Map<String, Object>> dataList) {
        validateModelCode(modelCode);
        assertWritable(modelCode);
        if (dataList == null || dataList.isEmpty()) {
            throw new MetaServiceException("Data list cannot be null or empty");
        }

        logOperation("batchCreate", modelCode, dataList.size());

        ModelDefinition model = getModelDefinition(modelCode);
        FieldDefinition primaryKey = metadataService.getPrimaryKeyField(modelCode);

        DynamicBatchResponse response = new DynamicBatchResponse();
        response.setTotal(dataList.size());

        int successCount = 0;
        int failedCount = 0;
        List<String> errors = new ArrayList<>();

        // No outer @Transactional — each create() runs in its own transaction
        for (int i = 0; i < dataList.size(); i++) {
            try {
                Map<String, Object> data = dataList.get(i);

                // Check if record already exists (idempotent behavior)
                Object primaryKeyValue = data.get(primaryKey.getCode());
                if (primaryKeyValue != null) {
                    try {
                        Map<String, Object> existingRecord = getById(modelCode, primaryKeyValue.toString());
                        if (existingRecord != null) {
                            log.info("Record with primary key {} already exists, skipping creation", logSafe(primaryKeyValue));
                            successCount++;
                            continue;
                        }
                    } catch (MetaServiceException e) {
                        if (!isRecordNotFound(e)) {
                            throw e;
                        }
                        // Record does not exist, proceed with creation.
                    }
                }

                create(modelCode, data);
                successCount++;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // Reliable duplicate key detection via exception type, not string matching
                log.info("Duplicate key detected for row {}, treating as success", i + 1);
                successCount++;
            } catch (Exception e) {
                failedCount++;
                errors.add("Row " + (i + 1) + ": " + e.getMessage());
                log.warn("Batch create failed for row {}: {}", i + 1, logSafe(e.getMessage()), e);
            }
        }

        response.setSuccess(successCount);
        response.setFailed(failedCount);
        response.setErrors(errors);

        return response;
    }

    /**
     * Bulk create fast path. Runs the same per-row validation/enrichment/PK/type-conversion as
     * {@link #create} but accumulates all rows into ONE multi-row INSERT inside a single
     * transaction, and skips the per-row post-insert tail (getById reload, change-log, automation
     * triggers, SLA activation, virtual-field materialization). Returns the enriched rows (with
     * generated primary keys) in input order so callers can correlate ids without a select-back.
     *
     * <p>Intended for mechanical bulk loads (BOM import). Per-row side effects that {@code create}
     * fires are intentionally NOT run here — do not use for models that rely on create-time
     * automations/SLA. Any downstream recompute (pricing, process-fee, rollup) materializes
     * computed fields afterward.
     */
    @Override
    @Transactional
    public List<Map<String, Object>> bulkCreate(String modelCode, List<Map<String, Object>> dataList) {
        validateModelCode(modelCode);
        assertWritable(modelCode);
        if (dataList == null || dataList.isEmpty()) {
            throw new MetaServiceException("Data list cannot be null or empty");
        }

        logOperation("bulkCreate", modelCode, dataList.size());

        ModelDefinition model = getModelDefinition(modelCode);
        ensureTableExists(modelCode);
        FieldDefinition primaryKey = metadataService.getPrimaryKeyField(modelCode);
        Set<String> jsonbColumns = JsonbFieldHelper.getJsonbHostColumns(model);

        Object currentUserId = getCurrentUserId();
        Object currentTenantId = getCurrentTenantId();
        java.time.Instant now = java.time.Instant.now();

        List<Map<String, Object>> columnDataList = new ArrayList<>(dataList.size());
        List<Map<String, Object>> createdRecords = new ArrayList<>(dataList.size());

        for (Map<String, Object> input : dataList) {
            if (input == null || input.isEmpty()) {
                throw new MetaServiceException("Data cannot be null or empty");
            }
            // Per-row prefix identical to create(): strip → normalize → validate → enrich → PK →
            // convert types → filter virtual → toColumnData. Work on a copy so caller maps stay intact.
            Map<String, Object> data = new HashMap<>(input);
            stripNonWritableFields(modelCode, data);
            payloadTemporalNormalizer.normalize(data, model);
            validationService.validateAndThrow(model, data, ValidationContext.CREATE);

            Map<String, Object> enrichedData = new HashMap<>(data);
            enrichedData.put("created_at", now);
            enrichedData.put("created_by", currentUserId);
            enrichedData.put("updated_at", now);
            enrichedData.put("updated_by", currentUserId);
            enrichedData.put("tenant_id", currentTenantId);

            if (!enrichedData.containsKey(primaryKey.getCode())) {
                enrichedData.put(primaryKey.getCode(), typeSystemManager.generatePrimaryKey(primaryKey));
            }

            enrichedData = convertDataTypes(model, enrichedData);
            filterVirtualFields(model, enrichedData);

            columnDataList.add(toColumnData(model, enrichedData));
            createdRecords.add(enrichedData); // carries generated PK, in input order
        }

        int inserted = jsonbColumns.isEmpty()
                ? dynamicDataMapper.batchInsert(model.getTableName(), columnDataList)
                : dynamicDataMapper.batchInsertWithJsonb(model.getTableName(), columnDataList, jsonbColumns);
        if (inserted != dataList.size()) {
            throw new MetaServiceException(
                    "Bulk create expected " + dataList.size() + " rows inserted but got " + inserted);
        }

        return createdRecords;
    }

    private boolean isRecordNotFound(MetaServiceException e) {
        String message = e.getMessage();
        return message != null && message.startsWith("Record not found:");
    }

    @Override
    @Transactional
    public DynamicBatchResponse batchUpdate(String modelCode, List<Map<String, Object>> dataList) {
        validateModelCode(modelCode);
        assertWritable(modelCode);
        if (dataList == null || dataList.isEmpty()) {
            throw new MetaServiceException("Data list cannot be null or empty");
        }

        logOperation("batchUpdate", modelCode, dataList.size());
        
        ModelDefinition model = getModelDefinition(modelCode);
        FieldDefinition primaryKey = metadataService.getPrimaryKeyField(modelCode);
        
        DynamicBatchResponse response = new DynamicBatchResponse();
        response.setTotal(dataList.size());
        
        int successCount = 0;
        int failedCount = 0;
        List<String> errors = new ArrayList<>();
        
        for (int i = 0; i < dataList.size(); i++) {
            try {
                Map<String, Object> data = dataList.get(i);
                Object recordId = data.get(primaryKey.getCode());
                if (recordId == null) {
                    throw new MetaServiceException("Primary key is required for update");
                }
                
                update(modelCode, recordId.toString(), data);
                successCount++;
            } catch (Exception e) {
                failedCount++;
                errors.add("Row " + (i + 1) + ": " + e.getMessage());
                log.warn("Batch update failed for row {}: {}", i + 1, logSafe(e.getMessage()), e);
            }
        }
        
        response.setSuccess(successCount);
        response.setFailed(failedCount);
        response.setErrors(errors);
        
        return response;
    }

    @Override
    @Transactional
    public void batchDelete(String modelCode, List<String> recordIds) {
        validateModelCode(modelCode);
        assertWritable(modelCode);
        if (recordIds == null || recordIds.isEmpty()) {
            throw new MetaServiceException("Record IDs cannot be null or empty");
        }

        logOperation("batchDelete", modelCode, recordIds.size());

        ModelDefinition model = getModelDefinition(modelCode);
        FieldDefinition primaryKey = metadataService.getPrimaryKeyField(modelCode);
        String tableName = SqlSafetyUtils.requireIdentifier(model.getTableName(), "table name");
        String primaryKeyColumn = SqlSafetyUtils.requireIdentifier(
                primaryKey.getColumnName() != null ? primaryKey.getColumnName() : primaryKey.getCode(),
                "primary key column");

        Long tenantId = getCurrentTenantId();
        Long userId = getCurrentUserId();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        StringBuilder sql = new StringBuilder("DELETE FROM ")
                .append(tableName)
                .append(" WHERE tenant_id = #{params.tenantId}")
                .append(" AND ")
                .append(primaryKeyColumn)
                .append(" IN (");
        for (int i = 0; i < recordIds.size(); i++) {
            String recordId = recordIds.get(i);
            if (recordId == null || recordId.isBlank()) {
                throw new MetaServiceException("Record ID cannot be null or empty");
            }
            if (i > 0) {
                sql.append(", ");
            }
            String paramName = "id" + i;
            sql.append("#{params.").append(paramName).append("}");
            params.put(paramName, recordId);
        }
        sql.append(")");

        if (!MetaContext.isDataPermissionBypassed()) {
            try {
                String rowFilter = dataPermissionEngine.buildRowFilter(tenantId, modelCode, userId);
                appendScopedBulkFilter(sql, rowFilter);
            } catch (Exception e) {
                log.error("Failed to apply row-level data permission for batch delete on model {} — denying access",
                        logSafe(modelCode), e);
                throw new MetaServiceException("Data permission evaluation failed for model: " + modelCode, e);
            }

            try {
                String domainFilter = dataDomainService.buildDomainFilter(modelCode, userId);
                appendScopedBulkFilter(sql, domainFilter);
            } catch (Exception e) {
                log.error("Failed to apply domain filter for batch delete on model {} — denying access",
                        logSafe(modelCode), e);
                throw new MetaServiceException("Data domain filter evaluation failed for model: " + modelCode, e);
            }
        }

        int affected = dynamicDataMapper.deleteByQuery(sql.toString(), params);
        if (affected != recordIds.size()) {
            throw new MetaServiceException(
                    "Batch delete denied: only " + affected + " of " + recordIds.size()
                            + " requested records matched tenant and data scope");
        }

        log.info("Batch deleted {} records from model: {}", recordIds.size(), logSafe(modelCode));
    }

    private void appendScopedBulkFilter(StringBuilder sql, String filter) {
        if (filter == null || filter.isBlank()) {
            return;
        }
        String normalized = filter.trim();
        if (normalized.regionMatches(true, 0, "AND ", 0, 4)) {
            normalized = normalized.substring(4).trim();
        } else if (normalized.regionMatches(true, 0, "WHERE ", 0, 6)) {
            normalized = normalized.substring(6).trim();
        }
        if (normalized.isBlank()) {
            return;
        }
        rejectStatementInjectionMarkers(normalized);
        sql.append(" AND ").append(normalized);
    }

    private void rejectStatementInjectionMarkers(String filter) {
        if (filter.contains(";") || filter.contains("--") || filter.contains("/*") || filter.contains("*/")) {
            throw new MetaServiceException("Unsafe data scope filter for batch delete");
        }
    }

    // ==================== Custom Query ====================

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> executeCustomQuery(String modelCode, String queryName, Map<String, Object> queryParams) {
        validateModelCode(modelCode);
        logOperation("executeCustomQuery", modelCode, queryName);

        NamedQueryTestRequest testRequest = new NamedQueryTestRequest();
        testRequest.setParameters(queryParams != null ? queryParams : Collections.emptyMap());
        testRequest.setSize(1000);
        testRequest.setPage(1);

        PaginationResult<Map<String, Object>> result = namedQueryService.executeQuery(queryName, testRequest);
        return result.getRecords() != null ? result.getRecords() : Collections.emptyList();
    }

    // ==================== Aggregate ====================

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> aggregate(String modelCode, AggregateRequest aggregateRequest) {
        validateModelCode(modelCode);
        logOperation("aggregate", modelCode, aggregateRequest);

        ModelDefinition model = getModelDefinition(modelCode);

        // Build aggregate query
        QueryBuilderService.QueryBuilder queryBuilder = queryBuilderService.buildAggregateQuery(model, aggregateRequest);

        // Add conditions if present
        if (aggregateRequest.getConditions() != null) {
            for (QueryCondition condition : aggregateRequest.getConditions()) {
                queryBuilder.addCondition(condition.getFieldName(), condition.getOperator().name(), condition.getValue());
            }
        }

        // Add tenant isolation
        Long tenantId = getCurrentTenantId();
        Long userId = getCurrentUserId();
        queryBuilder.addCondition("tenant_id", QueryCondition.Operator.EQ.name(), tenantId);

        // Row-level permission filter (fail-secure)
        try {
            String rowFilter = dataPermissionEngine.buildRowFilter(tenantId, modelCode, userId);
            if (rowFilter != null && !rowFilter.isBlank()) {
                queryBuilder.addRawCondition(rowFilter);
            }
        } catch (Exception e) {
            log.error("Failed to apply row-level data permission in aggregate for model {} — denying access", logSafe(modelCode), e);
            throw new MetaServiceException("Data permission evaluation failed for aggregate: " + modelCode, e);
        }

        // Domain isolation filter (fail-secure)
        try {
            String domainFilter = dataDomainService.buildDomainFilter(modelCode, userId);
            if (domainFilter != null && !domainFilter.isBlank()) {
                queryBuilder.addRawCondition(domainFilter);
            }
        } catch (Exception e) {
            log.error("Failed to apply domain filter in aggregate for model {} — denying access", logSafe(modelCode), e);
            throw new MetaServiceException("Data domain filter evaluation failed for aggregate: " + modelCode, e);
        }

        // Add GROUP BY if present
        String sql = queryBuilder.getSql();
        if (aggregateRequest.getGroupByFields() != null && !aggregateRequest.getGroupByFields().isEmpty()) {
            List<String> groupColumns = aggregateRequest.getGroupByFields().stream()
                    .map(f -> resolveColumnName(model, f))
                    .collect(Collectors.toList());
            sql = sql + " GROUP BY " + String.join(", ", groupColumns);
        }

        // Add limit
        if (aggregateRequest.getLimit() != null && aggregateRequest.getLimit() > 0) {
            sql = sql + " LIMIT " + aggregateRequest.getLimit();
        }

        Map<String, Object> paramMap = queryBuilder.getParameterMap();
        List<Map<String, Object>> results = dynamicDataMapper.selectByQuery(sql, paramMap);

        if (results == null || results.isEmpty()) {
            return Collections.emptyMap();
        }

        // If no GROUP BY, return the single aggregate row
        if (aggregateRequest.getGroupByFields() == null || aggregateRequest.getGroupByFields().isEmpty()) {
            return results.get(0);
        }

        // With GROUP BY, return all results in a wrapper
        Map<String, Object> response = new HashMap<>();
        response.put("groups", results);
        response.put("groupCount", results.size());
        return response;
    }

    // ==================== Stats ====================

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getStats(String modelCode, Map<String, Object> statsParams) {
        validateModelCode(modelCode);
        logOperation("getStats", modelCode, statsParams);

        // Parse stats params
        @SuppressWarnings("unchecked")
        List<String> fields = statsParams != null ? (List<String>) statsParams.get("fields") : null;
        @SuppressWarnings("unchecked")
        List<String> functions = statsParams != null ? (List<String>) statsParams.get("functions") : null;

        // Build AggregateRequest
        List<AggregateRequest.AggregateField> aggregateFields = new ArrayList<>();

        // Default: count all records
        aggregateFields.add(AggregateRequest.AggregateField.builder()
                .fieldName("*")
                .function(AggregateRequest.AggregateFunction.COUNT)
                .alias("total_count")
                .build());

        // Add requested field/function combinations
        if (fields != null && functions != null) {
            for (String field : fields) {
                for (String function : functions) {
                    String alias = function.toLowerCase() + "_" + field;
                    AggregateRequest.AggregateFunction aggFunc =
                            AggregateRequest.AggregateFunction.valueOf(function.toUpperCase());
                    aggregateFields.add(AggregateRequest.AggregateField.builder()
                            .fieldName(field)
                            .function(aggFunc)
                            .alias(alias)
                            .build());
                }
            }
        }

        @SuppressWarnings("unchecked")
        List<String> groupByFields = statsParams != null ? (List<String>) statsParams.get("groupBy") : null;

        AggregateRequest aggregateRequest = AggregateRequest.builder()
                .aggregateFields(aggregateFields)
                .groupByFields(groupByFields)
                .build();

        return aggregate(modelCode, aggregateRequest);
    }

    // ==================== Relation Data ====================

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getRelationData(String modelCode, String recordId, String relationName, Map<String, Object> queryParams) {
        validateModelCode(modelCode);
        logOperation("getRelationData", modelCode, relationName);

        ModelDefinition model = getModelDefinition(modelCode);
        RelationDefinition relation = findRelation(model, relationName);

        Long tenantId = getCurrentTenantId();

        // Source record visibility gates relation traversal. Target rows are
        // filtered below; the source must also pass the same single-record scope.
        getById(modelCode, recordId);

        // Security: validate all relation SQL identifiers to prevent injection
        java.util.regex.Pattern NAME_PATTERN = java.util.regex.Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
        if (relation.getTargetTable() != null && !NAME_PATTERN.matcher(relation.getTargetTable()).matches()) {
            throw new com.auraboot.framework.exception.BusinessException("Invalid relation target table: " + relation.getTargetTable());
        }
        if (relation.getTargetField() != null && !NAME_PATTERN.matcher(relation.getTargetField()).matches()) {
            throw new com.auraboot.framework.exception.BusinessException("Invalid relation target field: " + relation.getTargetField());
        }
        if (relation.getSourceField() != null && !NAME_PATTERN.matcher(relation.getSourceField()).matches()) {
            throw new com.auraboot.framework.exception.BusinessException("Invalid relation source field: " + relation.getSourceField());
        }
        if (relation.getJoinTable() != null && !NAME_PATTERN.matcher(relation.getJoinTable()).matches()) {
            throw new com.auraboot.framework.exception.BusinessException("Invalid relation join table: " + relation.getJoinTable());
        }

        if (relation.getRelationType() == RelationDefinition.RelationType.MANY_TO_MANY) {
            // Many-to-many: query join table first, then target table
            String joinSql = "SELECT " + relation.getTargetField() + " FROM " + relation.getJoinTable()
                    + " WHERE " + relation.getSourceField() + " = #{params.recordId}"
                    + " AND tenant_id = #{params.tenantId}";
            Map<String, Object> joinParams = new HashMap<>();
            joinParams.put("recordId", recordId);
            joinParams.put("tenantId", tenantId);

            List<Map<String, Object>> joinResults = dynamicDataMapper.selectByQuery(joinSql, joinParams);
            if (joinResults.isEmpty()) {
                return Collections.emptyList();
            }

            // Extract target IDs
            List<Object> targetIds = joinResults.stream()
                    .map(row -> row.get(relation.getTargetField()))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            if (targetIds.isEmpty()) {
                return Collections.emptyList();
            }

            // Query target table — use parameterized IN clause to prevent SQL injection
            Map<String, Object> targetParams = new HashMap<>();
            targetParams.put("tenantId", tenantId);

            StringBuilder inPlaceholders = new StringBuilder();
            for (int i = 0; i < targetIds.size(); i++) {
                if (i > 0) inPlaceholders.append(",");
                String paramKey = "id_" + i;
                inPlaceholders.append("#{params.").append(paramKey).append("}");
                targetParams.put(paramKey, targetIds.get(i));
            }

            StringBuilder targetSqlBuilder = new StringBuilder();
            targetSqlBuilder.append("SELECT * FROM ").append(relation.getTargetTable())
                    .append(" WHERE id IN (").append(inPlaceholders).append(")")
                    .append(" AND tenant_id = #{params.tenantId}");

            // Row-level permission filter on target model (fail-secure)
            String targetModelCode = relation.getTargetModel();
            Long userId = getCurrentUserId();
            try {
                String rowFilter = dataPermissionEngine.buildRowFilter(tenantId, targetModelCode, userId);
                if (rowFilter != null && !rowFilter.isBlank()) {
                    targetSqlBuilder.append(" ").append(rowFilter);
                }
            } catch (Exception e) {
                log.error("Failed to apply row-level permission in getRelationData for target: {} — denying access", logSafe(targetModelCode), e);
                throw new MetaServiceException("Data permission evaluation failed for relation query", e);
            }

            // Domain isolation filter on target model (fail-secure)
            try {
                String domainFilter = dataDomainService.buildDomainFilter(targetModelCode, userId);
                if (domainFilter != null && !domainFilter.isBlank()) {
                    targetSqlBuilder.append(" ").append(domainFilter);
                }
            } catch (Exception e) {
                log.error("Failed to apply domain filter in getRelationData for target: {} — denying access", logSafe(targetModelCode), e);
                throw new MetaServiceException("Data domain filter failed for relation query", e);
            }

            List<Map<String, Object>> targetResults = dynamicDataMapper.selectByQuery(targetSqlBuilder.toString(), targetParams);

            // Column masking on target model results (fail-secure)
            try {
                List<FieldMaskRule> maskRules = dataPermissionEngine.getFieldMaskRules(tenantId, targetModelCode, userId);
                if (maskRules != null && !maskRules.isEmpty()) {
                    targetResults = dataPermissionEngine.applyFieldMasking(targetResults, maskRules);
                }
            } catch (Exception e) {
                log.error("Failed to apply field masking in getRelationData for target: {} — denying access", logSafe(targetModelCode), e);
                throw new MetaServiceException("Field masking failed for relation query", e);
            }

            // Read-shape contract: json/jsonb fields leave as JSON strings, never PGobject.
            JsonbFieldHelper.normalizeJsonReadValues(getModelDefinition(targetModelCode), targetResults);

            return targetResults;
        } else {
            // One-to-many / Many-to-one: direct query on target table
            StringBuilder sqlBuilder = new StringBuilder();
            sqlBuilder.append("SELECT * FROM ").append(relation.getTargetTable())
                    .append(" WHERE ").append(relation.getTargetField()).append(" = #{params.recordId}")
                    .append(" AND tenant_id = #{params.tenantId}");
            Map<String, Object> params = new HashMap<>();
            params.put("recordId", recordId);
            params.put("tenantId", tenantId);

            // Row-level permission filter on target model (fail-secure)
            String targetModelCode = relation.getTargetModel();
            Long userId = getCurrentUserId();
            try {
                String rowFilter = dataPermissionEngine.buildRowFilter(tenantId, targetModelCode, userId);
                if (rowFilter != null && !rowFilter.isBlank()) {
                    sqlBuilder.append(" ").append(rowFilter);
                }
            } catch (Exception e) {
                log.error("Failed to apply row-level permission in getRelationData for target: {} — denying access", logSafe(targetModelCode), e);
                throw new MetaServiceException("Data permission evaluation failed for relation query", e);
            }

            // Domain isolation filter on target model (fail-secure)
            try {
                String domainFilter = dataDomainService.buildDomainFilter(targetModelCode, userId);
                if (domainFilter != null && !domainFilter.isBlank()) {
                    sqlBuilder.append(" ").append(domainFilter);
                }
            } catch (Exception e) {
                log.error("Failed to apply domain filter in getRelationData for target: {} — denying access", logSafe(targetModelCode), e);
                throw new MetaServiceException("Data domain filter failed for relation query", e);
            }

            // Apply limit from queryParams
            if (queryParams != null && queryParams.containsKey("limit")) {
                sqlBuilder.append(" LIMIT ").append(Integer.parseInt(queryParams.get("limit").toString()));
            }

            List<Map<String, Object>> relationResults = dynamicDataMapper.selectByQuery(sqlBuilder.toString(), params);

            // Column masking on target model results (fail-secure)
            try {
                List<FieldMaskRule> maskRules = dataPermissionEngine.getFieldMaskRules(tenantId, targetModelCode, userId);
                if (maskRules != null && !maskRules.isEmpty()) {
                    relationResults = dataPermissionEngine.applyFieldMasking(relationResults, maskRules);
                }
            } catch (Exception e) {
                log.error("Failed to apply field masking in getRelationData for target: {} — denying access", logSafe(targetModelCode), e);
                throw new MetaServiceException("Field masking failed for relation query", e);
            }

            // Read-shape contract: json/jsonb fields leave as JSON strings, never PGobject.
            JsonbFieldHelper.normalizeJsonReadValues(getModelDefinition(targetModelCode), relationResults);

            return relationResults;
        }
    }

    // ==================== Relation CRUD ====================

    @Override
    @Transactional
    public RelationOperationResult createRelations(String modelCode, String recordId, String relationName, List<String> targetRecordIds) {
        validateModelCode(modelCode);
        logOperation("createRelations", modelCode, relationName);

        ModelDefinition model = getModelDefinition(modelCode);
        RelationDefinition relation = findRelation(model, relationName);

        if (relation.getRelationType() != RelationDefinition.RelationType.MANY_TO_MANY) {
            throw new MetaServiceException("createRelations only supports MANY_TO_MANY relations. Use update for other types.");
        }

        Long tenantId = getCurrentTenantId();
        List<String> successIds = new ArrayList<>();
        List<String> failedIds = new ArrayList<>();

        for (String targetId : targetRecordIds) {
            try {
                Map<String, Object> data = new HashMap<>();
                data.put(relation.getSourceField(), recordId);
                data.put(relation.getTargetField(), targetId);
                data.put("tenant_id", tenantId);
                data.put("created_at", java.time.Instant.now());

                dynamicDataMapper.insert(relation.getJoinTable(), data);
                successIds.add(targetId);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // Relation already exists — treat as success (idempotent)
                log.debug("Relation already exists for target {}, treating as success", logSafe(targetId));
                successIds.add(targetId);
            } catch (Exception e) {
                log.warn("Failed to create relation for target {}: {}", logSafe(targetId), logSafe(e.getMessage()), e);
                failedIds.add(targetId);
            }
        }

        boolean allSuccess = failedIds.isEmpty();
        return RelationOperationResult.builder()
                .success(allSuccess)
                .operationType(RelationOperationResult.OperationType.CREATE_RELATION)
                .successCount(successIds.size())
                .failedCount(failedIds.size())
                .successRecordIds(successIds)
                .failedRecordIds(failedIds)
                .errorMessage(allSuccess ? null : "Some relations failed to create")
                .build();
    }

    @Override
    @Transactional
    public RelationOperationResult removeRelations(String modelCode, String recordId, String relationName, List<String> targetRecordIds) {
        validateModelCode(modelCode);
        logOperation("removeRelations", modelCode, relationName);

        ModelDefinition model = getModelDefinition(modelCode);
        RelationDefinition relation = findRelation(model, relationName);

        if (relation.getRelationType() != RelationDefinition.RelationType.MANY_TO_MANY) {
            throw new MetaServiceException("removeRelations only supports MANY_TO_MANY relations.");
        }

        Long tenantId = getCurrentTenantId();
        List<String> successIds = new ArrayList<>();
        List<String> failedIds = new ArrayList<>();

        for (String targetId : targetRecordIds) {
            try {
                Map<String, Object> conditions = new HashMap<>();
                conditions.put(relation.getSourceField(), recordId);
                conditions.put(relation.getTargetField(), targetId);
                conditions.put("tenant_id", tenantId);

                int deleted = dynamicDataMapper.delete(relation.getJoinTable(), conditions);
                if (deleted > 0) {
                    successIds.add(targetId);
                } else {
                    failedIds.add(targetId);
                }
            } catch (Exception e) {
                log.warn("Failed to remove relation for target {}: {}", logSafe(targetId), logSafe(e.getMessage()), e);
                failedIds.add(targetId);
            }
        }

        boolean allSuccess = failedIds.isEmpty();
        return RelationOperationResult.builder()
                .success(allSuccess)
                .operationType(RelationOperationResult.OperationType.REMOVE_RELATION)
                .successCount(successIds.size())
                .failedCount(failedIds.size())
                .successRecordIds(successIds)
                .failedRecordIds(failedIds)
                .errorMessage(allSuccess ? null : "Some relations failed to remove")
                .build();
    }

    // ==================== Validation ====================

    @Override
    public ValidationResult validate(String modelCode, Map<String, Object> data, ValidationContext validationContext) {
        validateModelCode(modelCode);

        ModelDefinition model = getModelDefinition(modelCode);
        return validationService.validateData(model, data, validationContext);
    }

    // ==================== Field Options ====================

    private record ReferenceOptionTarget(
            String targetModelCode,
            String targetTable,
            String valueColumn,
            String displayExpression,
            String displayAlias,
            String groupColumn) {
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rawRefTargetMap(FieldDefinition fieldDef) {
        Map<String, Object> extra = fieldDef == null ? null : fieldDef.getExtraProps();
        if (extra == null || extra.isEmpty()) {
            return null;
        }
        Object raw = extra.get("refTarget");
        if (!(raw instanceof Map<?, ?>) && extra.get("extension") instanceof Map<?, ?> extension) {
            raw = extension.get("refTarget");
        }
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private ReferenceOptionTarget resolveReferenceOptionTarget(FieldDefinition fieldDef) {
        FieldDefinition.RefTarget canonical = fieldDef == null ? null : fieldDef.getRefTarget();
        Map<String, Object> raw = rawRefTargetMap(fieldDef);

        String targetModelCode = firstText(
                canonical == null ? null : canonical.getTargetEntity(),
                readMapText(raw, "targetEntity", "targetModel", "targetModelCode", "modelCode", "refModelCode"));
        String targetTable = firstText(
                canonical == null ? null : canonical.getTargetTable(),
                readMapText(raw, "targetTable", "table"));
        String valueField = firstText(
                canonical == null ? null : canonical.getValueField(),
                readMapText(raw, "valueField", "targetValueField"),
                "pid");
        String displayField = firstText(
                canonical == null ? null : canonical.getDisplayField(),
                readMapText(raw, "displayField", "refDisplayField", "targetField", "fieldCode"),
                "name");

        if (!hasText(targetModelCode) && !hasText(targetTable)) {
            return null;
        }

        Optional<ModelDefinition> targetModelOpt = hasText(targetModelCode)
                ? metadataService.getModelDefinition(targetModelCode)
                : Optional.empty();
        String resolvedTargetTable = firstText(
                targetTable,
                targetModelOpt.map(ModelDefinition::getTableName).orElse(null),
                resolveSystemTable(targetModelCode));
        if (!hasText(resolvedTargetTable)) {
            return null;
        }

        String valueColumn = resolveReferenceValueColumn(targetModelOpt.orElse(null), valueField);
        String[] displayColumn = resolveDisplayColumnExpression(targetModelOpt, targetModelCode, displayField);
        String groupColumn = resolveReferenceValueColumn(
                targetModelOpt.orElse(null),
                firstText(readMapText(raw, "groupField"), "group_code"));
        return new ReferenceOptionTarget(
                targetModelCode,
                resolvedTargetTable,
                valueColumn,
                displayColumn[0],
                displayColumn[1],
                groupColumn);
    }

    private String resolveReferenceValueColumn(ModelDefinition targetModel, String configuredValueField) {
        String valueField = hasText(configuredValueField) ? configuredValueField : "pid";
        if (targetModel != null && targetModel.getFields() != null) {
            for (FieldDefinition field : targetModel.getFields()) {
                String columnName = field.getColumnName() != null ? field.getColumnName() : field.getCode();
                if (valueField.equals(field.getCode()) || valueField.equals(columnName)) {
                    return columnName;
                }
            }
        }
        return valueField;
    }

    private String readMapText(Map<String, Object> source, String... keys) {
        if (source == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && hasText(String.valueOf(value))) {
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
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    @Transactional(readOnly = true)
    public List<FieldOption> getFieldOptions(String modelCode, String fieldCode, FieldOptionRequest optionRequest) {
        validateModelCode(modelCode);
        logOperation("getFieldOptions", modelCode, fieldCode);

        ModelDefinition model = getModelDefinition(modelCode);
        FieldDefinition fieldDef = findFieldDefinition(model, fieldCode);
        ReferenceOptionTarget target = resolveReferenceOptionTarget(fieldDef);
        if (target == null) {
            return Collections.emptyList();
        }

        // Security: validate SQL identifiers to prevent injection
        java.util.regex.Pattern NAME_PATTERN = java.util.regex.Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
        if (!NAME_PATTERN.matcher(target.targetTable()).matches()
                || !NAME_PATTERN.matcher(target.valueColumn()).matches()
                || !NAME_PATTERN.matcher(target.displayAlias()).matches()) {
            log.warn("Invalid SQL identifier in refTarget config: table={}, value={}, display={}",
                    logSafe(target.targetTable()), logSafe(target.valueColumn()), logSafe(target.displayAlias()));
            return Collections.emptyList();
        }

        Long tenantId = getCurrentTenantId();
        int limit = optionRequest != null && optionRequest.getLimit() != null ? optionRequest.getLimit() : 50;
        int offset = optionRequest != null && optionRequest.getOffset() != null ? optionRequest.getOffset() : 0;

        // Build query
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(target.valueColumn()).append(", ")
                .append(target.displayExpression()).append(" AS ").append(target.displayAlias());
        sql.append(" FROM ").append(target.targetTable());
        sql.append(" WHERE tenant_id = #{params.tenantId}");

        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);

        // Row-level permission filter on reference target model (fail-secure)
        try {
            String refModelCode = target.targetModelCode();
            if (refModelCode != null) {
                Long userId = getCurrentUserId();
                String rowFilter = dataPermissionEngine.buildRowFilter(tenantId, refModelCode, userId);
                if (rowFilter != null && !rowFilter.isBlank()) {
                    sql.append(" ").append(rowFilter);
                }
            }
        } catch (Exception e) {
            log.error("Failed to apply row-level permission in getFieldOptions — denying access", e);
            throw new MetaServiceException("Data permission evaluation failed for field options", e);
        }

        // Add keyword filter
        if (optionRequest != null && optionRequest.getKeyword() != null && !optionRequest.getKeyword().isBlank()) {
            sql.append(" AND ").append(target.displayExpression()).append(" ILIKE #{params.keyword}");
            params.put("keyword", "%" + optionRequest.getKeyword() + "%");
        }

        // Add group filter
        if (optionRequest != null && optionRequest.getGroup() != null && !optionRequest.getGroup().isBlank()) {
            String groupField = target.groupColumn();
            if (!hasText(groupField) || !NAME_PATTERN.matcher(groupField).matches()) {
                log.warn("Invalid SQL identifier for groupField: {}", logSafe(groupField));
                return Collections.emptyList();
            }
            sql.append(" AND ").append(groupField).append(" = #{params.groupValue}");
            params.put("groupValue", optionRequest.getGroup());
        }

        sql.append(" ORDER BY ").append(target.displayAlias());
        sql.append(" LIMIT ").append(limit);
        sql.append(" OFFSET ").append(offset);

        List<Map<String, Object>> results = dynamicDataMapper.selectByQuery(sql.toString(), params);

        // Convert to FieldOption list
        List<FieldOption> options = new ArrayList<>();
        int sortOrder = offset;
        for (Map<String, Object> row : results) {
            options.add(FieldOption.builder()
                    .value(row.get(target.valueColumn()) != null ? row.get(target.valueColumn()).toString() : null)
                    .label(row.get(target.displayAlias()) != null ? row.get(target.displayAlias()).toString() : null)
                    .sortOrder(sortOrder++)
                    .build());
        }

        return options;
    }

    // ==================== Export ====================

    @Override
    @Transactional(readOnly = true)
    public ExportResult exportData(String modelCode, DataExportRequest exportRequest) {
        validateModelCode(modelCode);
        logOperation("exportData", modelCode, exportRequest);

        Instant startTime = Instant.now();
        ModelDefinition model = getModelDefinition(modelCode);

        // Permission checks BEFORE outer try — failures must NOT be swallowed
        Long tenantId = getCurrentTenantId();
        Long userId = getCurrentUserId();

        String rowFilter;
        try {
            rowFilter = dataPermissionEngine.buildRowFilter(tenantId, modelCode, userId);
        } catch (Exception e) {
            log.error("Failed to apply row-level data permission in export for model {} — denying access", logSafe(modelCode), e);
            throw new MetaServiceException("Data permission evaluation failed for export: " + modelCode, e);
        }

        String domainFilter;
        try {
            domainFilter = dataDomainService.buildDomainFilter(modelCode, userId);
        } catch (Exception e) {
            log.error("Failed to apply domain filter in export for model {} — denying access", logSafe(modelCode), e);
            throw new MetaServiceException("Data domain filter failed for export: " + modelCode, e);
        }

        try {
            // Build query for export data
            List<QueryCondition> conditions = exportRequest.getConditions() != null
                    ? exportRequest.getConditions() : Collections.emptyList();
            QueryBuilderService.QueryBuilder queryBuilder = queryBuilderService.buildConditionQuery(model, conditions);
            queryBuilder.addCondition("tenant_id", QueryCondition.Operator.EQ.name(), tenantId);

            // Apply row-level permission filter
            if (rowFilter != null && !rowFilter.isBlank()) {
                queryBuilder.addRawCondition(rowFilter);
            }

            // Apply domain isolation filter
            if (domainFilter != null && !domainFilter.isBlank()) {
                queryBuilder.addRawCondition(domainFilter);
            }

            // Add sort
            if (exportRequest.getSortFields() != null && !exportRequest.getSortFields().isEmpty()) {
                List<SortField> mappedSortFields = mapSortFields(model, exportRequest.getSortFields());
                queryBuilder = queryBuilderService.buildOrderQuery(queryBuilder, mappedSortFields, model);
            }

            // Add limit
            if (exportRequest.getLimit() != null && exportRequest.getLimit() > 0) {
                queryBuilder.setLimit(exportRequest.getLimit());
            }

            String sql = queryBuilder.getSql();
            Map<String, Object> paramMap = queryBuilder.getParameterMap();
            List<Map<String, Object>> data = dynamicDataMapper.selectByQuery(sql, paramMap);

            // Apply policy-based field masking (fail-secure)
            try {
                List<FieldMaskRule> maskRules = dataPermissionEngine.getFieldMaskRules(tenantId, modelCode, userId);
                if (maskRules != null && !maskRules.isEmpty()) {
                    data = dataPermissionEngine.applyFieldMasking(data, maskRules);
                }
            } catch (Exception e) {
                log.error("Failed to apply policy-based masking in export for model {} — denying access", logSafe(modelCode), e);
                throw new MetaServiceException("Policy-based masking failed for export: " + modelCode, e);
            }

            // Apply configurable field masking for export (A9)
            try {
                data = fieldMaskService.applyMaskingForExport(modelCode, data, userId);
            } catch (Exception e) {
                log.warn("Failed to apply export masking for model {}: {}", logSafe(modelCode), logSafe(e.getMessage()), e);
            }

            // Resolve reference display names so the export shows names, not pids (same as list/detail).
            enrichReferenceDisplayFields(modelCode, data);

            // Determine export fields
            List<String> exportFields = exportRequest.getFields();
            if (exportFields == null || exportFields.isEmpty()) {
                exportFields = model.getFields().stream()
                        .map(FieldDefinition::getCode)
                        .collect(Collectors.toList());
            }

            // Build field code → display label map for human-readable headers
            Map<String, String> fieldLabelMap = buildFieldLabelMap(model.getFields());

            // Generate export file
            DataExportRequest.ExportFormat format = exportRequest.getFormat() != null
                    ? exportRequest.getFormat() : DataExportRequest.ExportFormat.CSV;
            String fileName = exportRequest.getFileName() != null
                    ? exportRequest.getFileName()
                    : modelCode + "_export_" + System.currentTimeMillis();

            Path tempFile;
            switch (format) {
                case EXCEL:
                    tempFile = exportAsExcel(data, exportFields, fieldLabelMap, fileName, exportRequest.getIncludeHeader());
                    break;
                case JSON:
                    tempFile = exportAsJson(data, exportFields, fieldLabelMap, fileName);
                    break;
                case CSV:
                default:
                    tempFile = exportAsCsv(data, exportFields, fieldLabelMap, fileName, exportRequest.getIncludeHeader());
                    break;
            }

            long fileSize = Files.size(tempFile);
            return ExportResult.builder()
                    .success(true)
                    .filePath(tempFile.toString())
                    .recordCount((long) data.size())
                    .fileSize(fileSize)
                    .format(format.name())
                    .exportTime(startTime)
                    .build();

        } catch (MetaServiceException e) {
            throw e; // Never swallow permission failures
        } catch (Exception e) {
            log.error("Export failed for model {}: {}", logSafe(modelCode), logSafe(e.getMessage()), e);
            return ExportResult.builder()
                    .success(false)
                    .errorMessage("Export failed: " + e.getMessage())
                    .format(exportRequest.getFormat() != null ? exportRequest.getFormat().name() : "csv")
                    .build();
        }
    }

    // ==================== Import ====================

    @Override
    @Transactional
    public ImportResult importData(String modelCode, DataImportRequest importRequest) {
        validateModelCode(modelCode);
        assertWritable(modelCode);
        logOperation("importData", modelCode, importRequest);

        Instant startTime = Instant.now();
        ModelDefinition model = getModelDefinition(modelCode);

        try {
            // Validate file exists
            Path filePath = Paths.get(importRequest.getFilePath());
            if (!Files.exists(filePath)) {
                return ImportResult.builder()
                        .success(false)
                        .summary("Import file not found: " + importRequest.getFilePath())
                        .build();
            }

            // Parse file to data list
            List<Map<String, Object>> records;
            DataImportRequest.ImportFormat format = importRequest.getFormat() != null
                    ? importRequest.getFormat() : DataImportRequest.ImportFormat.CSV;

            switch (format) {
                case JSON:
                    records = parseJsonImport(filePath);
                    break;
                case CSV:
                default:
                    records = parseCsvImport(filePath, importRequest.getSkipFirstRow());
                    break;
            }

            // Apply field mapping
            Map<String, String> fieldMapping = importRequest.getFieldMapping();
            if (fieldMapping != null && !fieldMapping.isEmpty()) {
                records = records.stream()
                        .map(row -> applyFieldMapping(row, fieldMapping))
                        .collect(Collectors.toList());
            }

            // Batch insert
            int batchSize = importRequest.getBatchSize() != null ? importRequest.getBatchSize() : 100;
            int successCount = 0;
            int failedCount = 0;
            List<ImportResult.ImportError> errors = new ArrayList<>();
            Long tenantId = getCurrentTenantId();

            for (int i = 0; i < records.size(); i += batchSize) {
                int end = Math.min(i + batchSize, records.size());
                List<Map<String, Object>> batch = records.subList(i, end);

                for (int j = 0; j < batch.size(); j++) {
                    int rowIndex = i + j;
                    try {
                        Map<String, Object> record = batch.get(j);
                        // Add system columns
                        Map<String, Object> columnData = toColumnData(model, record);
                        columnData.put("tenant_id", tenantId);
                        columnData.put("created_at", Instant.now());
                        columnData.put("created_by", getCurrentUserId());

                        Set<String> batchJsonbCols = JsonbFieldHelper.getJsonbHostColumns(model);
                        if (batchJsonbCols.isEmpty()) {
                            dynamicDataMapper.insert(model.getTableName(), columnData);
                        } else {
                            dynamicDataMapper.insertWithJsonb(model.getTableName(), columnData, batchJsonbCols);
                        }
                        successCount++;
                    } catch (Exception e) {
                        failedCount++;
                        errors.add(ImportResult.ImportError.builder()
                                .rowNumber(rowIndex + 1)
                                .fieldName(null)
                                .errorMessage(e.getMessage())
                                .build());
                    }
                }
            }

            return ImportResult.builder()
                    .success(failedCount == 0)
                    .totalCount(records.size())
                    .successCount(successCount)
                    .failedCount(failedCount)
                    .errors(errors)
                    .importTime(startTime)
                    .summary(String.format("Imported %d/%d records", successCount, records.size()))
                    .build();

        } catch (Exception e) {
            log.error("Import failed for model {}: {}", logSafe(modelCode), logSafe(e.getMessage()), e);
            return ImportResult.builder()
                    .success(false)
                    .summary("Import failed: " + e.getMessage())
                    .build();
        }
    }

    // ==================== Custom Action ====================

    @Override
    @Transactional
    public ActionExecutionResult executeCustomAction(String modelCode, String actionName, Map<String, Object> actionParams) {
        validateModelCode(modelCode);
        logOperation("executeCustomAction", modelCode, actionName);

        Instant startTime = Instant.now();
        ModelDefinition model = getModelDefinition(modelCode);

        try {
            Map<String, Object> resultData = new HashMap<>();

            switch (actionName) {
                case "count": {
                    Long tenantId = getCurrentTenantId();
                    Long userId = getCurrentUserId();
                    StringBuilder sql = new StringBuilder("SELECT COUNT(*) as cnt FROM ")
                            .append(model.getTableName())
                            .append(" WHERE tenant_id = #{params.tenantId}");
                    Map<String, Object> params = new HashMap<>();
                    params.put("tenantId", tenantId);

                    if (!MetaContext.isDataPermissionBypassed()) {
                        String rowFilter = dataPermissionEngine.buildRowFilter(tenantId, modelCode, userId);
                        if (rowFilter != null && !rowFilter.isBlank()) {
                            sql.append(" ").append(rowFilter);
                        }
                        String domainFilter = dataDomainService.buildDomainFilter(modelCode, userId);
                        if (domainFilter != null && !domainFilter.isBlank()) {
                            sql.append(" ").append(domainFilter);
                        }
                    }

                    List<Map<String, Object>> results = dynamicDataMapper.selectByQuery(sql.toString(), params);
                    long count = results.isEmpty() ? 0 : ((Number) results.get(0).get("cnt")).longValue();
                    resultData.put("count", count);
                    break;
                }
                case "truncate": {
                    return ActionExecutionResult.builder()
                            .success(false)
                            .actionName(actionName)
                            .errorMessage("Unsupported action: " + actionName)
                            .executionTime(startTime)
                            .duration(java.time.Duration.between(startTime, Instant.now()).toMillis())
                            .build();
                }
                default:
                    return ActionExecutionResult.builder()
                            .success(false)
                            .actionName(actionName)
                            .errorMessage("Unsupported action: " + actionName)
                            .executionTime(startTime)
                            .duration(java.time.Duration.between(startTime, Instant.now()).toMillis())
                            .build();
            }

            return ActionExecutionResult.builder()
                    .success(true)
                    .actionName(actionName)
                    .resultData(resultData)
                    .message("Action '" + actionName + "' executed successfully")
                    .executionTime(startTime)
                    .duration(java.time.Duration.between(startTime, Instant.now()).toMillis())
                    .build();

        } catch (Exception e) {
            log.error("Custom action '{}' failed for model {}: {}",
                    logSafe(actionName), logSafe(modelCode), logSafe(e.getMessage()), e);
            return ActionExecutionResult.builder()
                    .success(false)
                    .actionName(actionName)
                    .errorMessage("Action failed: " + e.getMessage())
                    .executionTime(startTime)
                    .duration(java.time.Duration.between(startTime, Instant.now()).toMillis())
                    .build();
        }
    }

    // 私有辅助方法
    private ModelDefinition getModelDefinition(String modelCode) {
        return metadataService.getModelDefinition(modelCode)
                .orElseThrow(() -> new MetaServiceException("Model not found: " + modelCode));
    }

    /**
     * Phase 1 guard: reject write operations against virtual models.
     *
     * <p>Virtual models (sourceType != "physical", i.e. namedQuery/endpoint/sqlView)
     * are read-only in phase 1 per design §6.4. Phase 2 will introduce a Virtual
     * Writable Model abstraction with command binding + field mapping.
     *
     * <p>Null-safe: if the model definition is not yet registered (first create
     * with auto table provisioning) or sourceType is null, treats as physical
     * and allows the write — the downstream code paths will still validate
     * existence.
     */
    private void assertWritable(String modelCode) {
        ModelDefinition def = metadataService.getDefinitionByCode(modelCode);
        if (def == null) {
            return;
        }
        String sourceType = def.getSourceType();
        if (sourceType != null && !"physical".equals(sourceType)) {
            throw new MetaServiceException(
                "virtual model is read-only in phase 1: " + modelCode
                + " (sourceType=" + sourceType + ")");
        }
    }

    private Map<String, Object> toColumnData(ModelDefinition model, Map<String, Object> data) {
        // Step 1: Merge JSONB virtual fields into host columns
        Map<String, Object> mergedData = JsonbFieldHelper.mergeJsonbFields(model, data);

        // Step 2: Map field codes to column names (only for non-JSONB-virtual fields)
        Map<String, Object> columnData = new HashMap<>();
        Map<String, String> codeToColumn = new HashMap<>();
        Set<String> hostColumns = JsonbFieldHelper.getJsonbHostColumns(model);
        for (FieldDefinition field : model.getFields()) {
            if (!field.isJsonbVirtual()) {
                codeToColumn.put(field.getCode(), field.getColumnName());
                codeToColumn.put(field.getColumnName(), field.getColumnName());
            }
        }

        for (Map.Entry<String, Object> entry : mergedData.entrySet()) {
            String key = entry.getKey();
            if (SYSTEM_COLUMNS.contains(key)) {
                columnData.put(key, entry.getValue());
                continue;
            }
            String columnName = codeToColumn.get(key);
            if (columnName != null) {
                Object value = entry.getValue();
                // Serialize structured values for JSON/JSONB host columns.
                if (hostColumns.contains(columnName) && JsonbFieldHelper.shouldSerializeJsonValue(value)) {
                    columnData.put(columnName, JsonbFieldHelper.toJsonString(value));
                } else {
                    columnData.put(columnName, value);
                }
                continue;
            }
            // Could be a JSONB host column from mergeJsonbFields (key is already a column name)
            if (hostColumns.contains(key)) {
                Object value = entry.getValue();
                columnData.put(key, JsonbFieldHelper.shouldSerializeJsonValue(value) ? JsonbFieldHelper.toJsonString(value) : value);
                continue;
            }
            throw new MetaServiceException("Unknown field for model " + model.getCode() + ": " + key);
        }

        return columnData;
    }

    /**
     * toColumnData variant for UPDATE that preserves unmodified JSONB keys.
     */
    private Map<String, Object> toColumnDataForUpdate(ModelDefinition model, Map<String, Object> data, Map<String, Object> existingRecord) {
        // Step 1: Merge JSONB virtual fields, preserving unmodified keys from existing record
        Map<String, Object> mergedData = JsonbFieldHelper.mergeJsonbFieldsForUpdate(model, data, existingRecord);

        // Step 2: Same column mapping as toColumnData
        Map<String, Object> columnData = new HashMap<>();
        Map<String, String> codeToColumn = new HashMap<>();
        Set<String> hostColumns = JsonbFieldHelper.getJsonbHostColumns(model);
        for (FieldDefinition field : model.getFields()) {
            if (!field.isJsonbVirtual()) {
                codeToColumn.put(field.getCode(), field.getColumnName());
                codeToColumn.put(field.getColumnName(), field.getColumnName());
            }
        }

        for (Map.Entry<String, Object> entry : mergedData.entrySet()) {
            String key = entry.getKey();
            if (SYSTEM_COLUMNS.contains(key)) {
                columnData.put(key, entry.getValue());
                continue;
            }
            String columnName = codeToColumn.get(key);
            if (columnName != null) {
                Object value = entry.getValue();
                if (hostColumns.contains(columnName) && JsonbFieldHelper.shouldSerializeJsonValue(value)) {
                    columnData.put(columnName, JsonbFieldHelper.toJsonString(value));
                } else {
                    columnData.put(columnName, value);
                }
                continue;
            }
            if (hostColumns.contains(key)) {
                Object value = entry.getValue();
                columnData.put(key, JsonbFieldHelper.shouldSerializeJsonValue(value) ? JsonbFieldHelper.toJsonString(value) : value);
                continue;
            }
            throw new MetaServiceException("Unknown field for model " + model.getCode() + ": " + key);
        }

        return columnData;
    }

    private RelationDefinition findRelation(ModelDefinition model, String relationName) {
        if (model.getRelations() == null) {
            throw new MetaServiceException("Model " + model.getCode() + " has no relations defined");
        }
        return model.getRelations().stream()
                .filter(r -> relationName.equals(r.getName()))
                .findFirst()
                .orElseThrow(() -> new MetaServiceException(
                        "Relation '" + relationName + "' not found in model " + model.getCode()));
    }

    private FieldDefinition findFieldDefinition(ModelDefinition model, String fieldCode) {
        return model.getFields().stream()
                .filter(f -> fieldCode.equals(f.getCode()))
                .findFirst()
                .orElseThrow(() -> new MetaServiceException(
                        "Field '" + fieldCode + "' not found in model " + model.getCode()));
    }

    private String resolveColumnName(ModelDefinition model, String fieldName) {
        if (SYSTEM_COLUMNS.contains(fieldName) || "*".equals(fieldName)) {
            return fieldName;
        }
        for (FieldDefinition field : model.getFields()) {
            if (fieldName.equals(field.getCode()) || fieldName.equals(field.getColumnName())) {
                // JSONB virtual fields use their typed expression for WHERE/ORDER BY
                if (field.isJsonbVirtual()) {
                    return field.getJsonbFilterExpression();
                }
                return field.getColumnName();
            }
        }
        throw new MetaServiceException("Unknown field for model " + model.getCode() + ": " + fieldName);
    }

    private Path exportAsExcel(List<Map<String, Object>> data, List<String> fields,
                               Map<String, String> fieldLabelMap, String fileName, Boolean includeHeader)
            throws IOException {
        Path tempFile = Files.createTempFile(fileName, ".xlsx");
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.xssf.usermodel.XSSFSheet sheet = workbook.createSheet("Data");

            // Create default font with Chinese support
            org.apache.poi.xssf.usermodel.XSSFFont defaultFont = workbook.createFont();
            defaultFont.setFontName("Arial Unicode MS");
            defaultFont.setFontHeightInPoints((short) 11);

            // Create default cell style
            org.apache.poi.xssf.usermodel.XSSFCellStyle defaultStyle = workbook.createCellStyle();
            defaultStyle.setFont(defaultFont);

            int rowNum = 0;

            // Write header
            if (!Boolean.FALSE.equals(includeHeader)) {
                org.apache.poi.xssf.usermodel.XSSFRow headerRow = sheet.createRow(rowNum++);
                // Create header style
                org.apache.poi.xssf.usermodel.XSSFCellStyle headerStyle = workbook.createCellStyle();
                org.apache.poi.xssf.usermodel.XSSFFont headerFont = workbook.createFont();
                headerFont.setFontName("Arial Unicode MS");
                headerFont.setFontHeightInPoints((short) 11);
                headerFont.setBold(true);
                headerStyle.setFont(headerFont);
                headerStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.GREY_25_PERCENT.getIndex());
                headerStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);

                for (int i = 0; i < fields.size(); i++) {
                    org.apache.poi.xssf.usermodel.XSSFCell cell = headerRow.createCell(i);
                    cell.setCellValue(fieldLabelMap != null
                            ? fieldLabelMap.getOrDefault(fields.get(i), fields.get(i))
                            : fields.get(i));
                    cell.setCellStyle(headerStyle);
                }
            }

            // Write data rows
            for (Map<String, Object> row : data) {
                org.apache.poi.xssf.usermodel.XSSFRow dataRow = sheet.createRow(rowNum++);
                for (int i = 0; i < fields.size(); i++) {
                    org.apache.poi.xssf.usermodel.XSSFCell cell = dataRow.createCell(i);
                    cell.setCellStyle(defaultStyle);
                    Object val = row.get(fields.get(i));
                    if (val != null) {
                        if (val instanceof Number) {
                            cell.setCellValue(((Number) val).doubleValue());
                        } else if (val instanceof Boolean) {
                            cell.setCellValue((Boolean) val);
                        } else if (val instanceof java.util.Date) {
                            cell.setCellValue((java.util.Date) val);
                        } else if (val instanceof java.time.LocalDateTime) {
                            cell.setCellValue(val.toString());
                        } else if (val instanceof java.time.Instant) {
                            cell.setCellValue(val.toString());
                        } else {
                            cell.setCellValue(val.toString());
                        }
                    }
                }
            }

            // Auto-size columns (with minimum width for Chinese characters)
            for (int i = 0; i < fields.size(); i++) {
                sheet.autoSizeColumn(i);
                // Ensure minimum width for Chinese content
                int currentWidth = sheet.getColumnWidth(i);
                if (currentWidth < 3000) {
                    sheet.setColumnWidth(i, 3000);
                }
            }

            // Write to file
            try (java.io.OutputStream os = Files.newOutputStream(tempFile)) {
                workbook.write(os);
            }
        }
        return tempFile;
    }

    private Path exportAsCsv(List<Map<String, Object>> data, List<String> fields,
                             Map<String, String> fieldLabelMap, String fileName, Boolean includeHeader)
            throws IOException {
        Path tempFile = Files.createTempFile(fileName, ".csv");
        try (BufferedWriter writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
            // Write header with display labels
            if (!Boolean.FALSE.equals(includeHeader)) {
                List<String> headerLabels = fields.stream()
                        .map(f -> CsvSafetyUtils.escapeCsvCell(fieldLabelMap != null
                                ? fieldLabelMap.getOrDefault(f, f) : f))
                        .collect(Collectors.toList());
                writer.write(String.join(",", headerLabels));
                writer.newLine();
            }
            // Write data — escape every cell (formula-injection neutralization + RFC-4180)
            for (Map<String, Object> row : data) {
                List<String> values = fields.stream()
                        .map(field -> CsvSafetyUtils.escapeCsvCell(row.get(field)))
                        .collect(Collectors.toList());
                writer.write(String.join(",", values));
                writer.newLine();
            }
        }
        return tempFile;
    }

    private Path exportAsJson(List<Map<String, Object>> data, List<String> fields,
                              Map<String, String> fieldLabelMap, String fileName)
            throws IOException {
        Path tempFile = Files.createTempFile(fileName, ".json");
        // Filter to only include specified fields, using display labels as keys
        List<Map<String, Object>> filtered = data.stream()
                .map(row -> {
                    Map<String, Object> filteredRow = new LinkedHashMap<>();
                    for (String field : fields) {
                        String key = (fieldLabelMap != null)
                                ? fieldLabelMap.getOrDefault(field, field) : field;
                        filteredRow.put(key, row.get(field));
                    }
                    return filteredRow;
                })
                .collect(Collectors.toList());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), filtered);
        return tempFile;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonImport(Path filePath) throws IOException {
        Object parsed = objectMapper.readValue(filePath.toFile(), Object.class);
        if (parsed instanceof List) {
            return (List<Map<String, Object>>) parsed;
        }
        throw new MetaServiceException("JSON import file must contain an array of objects");
    }

    private List<Map<String, Object>> parseCsvImport(Path filePath, Boolean skipFirstRow) throws IOException {
        List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return Collections.emptyList();
        }

        // First line is header
        String[] headers = lines.get(0).split(",", -1);
        for (int i = 0; i < headers.length; i++) {
            headers[i] = headers[i].trim().replace("\"", "");
        }

        int startLine = Boolean.FALSE.equals(skipFirstRow) ? 0 : 1;
        List<Map<String, Object>> records = new ArrayList<>();
        for (int i = startLine; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;

            String[] values = parseCsvLine(line);
            Map<String, Object> record = new LinkedHashMap<>();
            for (int j = 0; j < headers.length && j < values.length; j++) {
                String val = values[j].trim();
                record.put(headers[j], val.isEmpty() ? null : val);
            }
            records.add(record);
        }
        return records;
    }

    private String[] parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                values.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values.toArray(new String[0]);
    }

    private Map<String, Object> applyFieldMapping(Map<String, Object> row, Map<String, String> fieldMapping) {
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String targetField = fieldMapping.getOrDefault(entry.getKey(), entry.getKey());
            mapped.put(targetField, entry.getValue());
        }
        return mapped;
    }

    private List<SortField> mapSortFields(ModelDefinition model, List<SortField> sortFields) {
        if (sortFields == null || sortFields.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, String> codeToColumn = new HashMap<>();
        for (FieldDefinition field : model.getFields()) {
            codeToColumn.put(field.getCode(), field.getColumnName());
            codeToColumn.put(field.getColumnName(), field.getColumnName());
        }

        List<SortField> mappedFields = new ArrayList<>();
        for (SortField sortField : sortFields) {
            String columnName = codeToColumn.get(sortField.getFieldName());
            if (columnName == null && !SYSTEM_COLUMNS.contains(sortField.getFieldName())) {
                throw new MetaServiceException("Unknown sort field for model " + model.getCode() + ": " + sortField.getFieldName());
            }
            mappedFields.add(SortField.builder()
                    .fieldName(SYSTEM_COLUMNS.contains(sortField.getFieldName()) ? sortField.getFieldName() : columnName)
                    .direction(sortField.getDirection())
                    .priority(sortField.getPriority())
                    .build());
        }
        return mappedFields;
    }

    // ==================== Joint Sub-Table Save ====================

    @Override
    @Transactional
    public JointSubTableSaveResponse saveWithRelations(String modelCode, JointSubTableSaveRequest request) {
        validateModelCode(modelCode);
        assertWritable(modelCode);
        if (request == null || request.getMasterData() == null) {
            throw new MetaServiceException("Request and master data cannot be null");
        }

        long startTime = System.currentTimeMillis();
        logOperation("saveWithRelations", modelCode, request.getMasterData().keySet());

        ModelDefinition masterModel = getModelDefinition(modelCode);
        FieldDefinition primaryKey = metadataService.getPrimaryKeyField(modelCode);
        String pkField = primaryKey.getCode();

        List<String> errors = new ArrayList<>();
        Map<String, Integer> subTableCounts = new HashMap<>();
        Map<String, List<Map<String, Object>>> savedRecords = new HashMap<>();
        Map<String, List<JointSubTableSaveResponse.SubTableError>> subTableErrors = new HashMap<>();

        try {
            // Step 1: Determine if this is create or update
            Object existingPkValue = request.getMasterData().get(pkField);
            boolean isUpdate = existingPkValue != null && !existingPkValue.toString().trim().isEmpty();
            JointSubTableSaveResponse.OperationType opType;
            Map<String, Object> savedMaster;
            String masterId;

            // Step 2: Save master record
            if (isUpdate) {
                opType = JointSubTableSaveResponse.OperationType.UPDATE;
                masterId = existingPkValue.toString();
                savedMaster = update(modelCode, masterId, request.getMasterData());
                log.info("Updated master record: model={}, id={}", logSafe(modelCode), logSafe(masterId));
            } else {
                opType = JointSubTableSaveResponse.OperationType.CREATE;
                savedMaster = create(modelCode, request.getMasterData());
                masterId = savedMaster.get(pkField).toString();
                log.info("Created master record: model={}, id={}", logSafe(modelCode), logSafe(masterId));
            }

            // Step 3: Process each sub-table
            if (request.getTables() != null && !request.getTables().isEmpty()) {
                for (Map.Entry<String, List<Map<String, Object>>> entry : request.getTables().entrySet()) {
                    String tableKey = entry.getKey();
                    List<Map<String, Object>> childRows = entry.getValue();

                    if (childRows == null) {
                        continue;
                    }

                    // Resolve relation name
                    String relationName = tableKey;
                    if (request.getRelationMappings() != null && request.getRelationMappings().containsKey(tableKey)) {
                        relationName = request.getRelationMappings().get(tableKey);
                    }

                    try {
                        // Find relation definition
                        RelationDefinition relation = findRelationByName(masterModel, relationName);
                        if (relation == null) {
                            errors.add("Relation '" + relationName + "' not found in model " + modelCode);
                            continue;
                        }

                        // Get target model
                        String targetModelCode = relation.getTargetModel();
                        ModelDefinition targetModel = getModelDefinition(targetModelCode);

                        // Delete existing child records if replace mode
                        if (Boolean.TRUE.equals(request.getReplaceExisting()) && isUpdate) {
                            deleteExistingChildRecords(relation, masterId);
                        }

                        // Save child records
                        List<Map<String, Object>> savedChildren = new ArrayList<>();
                        List<JointSubTableSaveResponse.SubTableError> rowErrors = new ArrayList<>();
                        int successCount = 0;

                        for (int i = 0; i < childRows.size(); i++) {
                            Map<String, Object> childData = new HashMap<>(childRows.get(i));

                            try {
                                // Inject foreign key
                                String fkField = relation.getTargetField();
                                childData.put(fkField, masterId);

                                // Create child record
                                Map<String, Object> savedChild = create(targetModelCode, childData);
                                savedChildren.add(savedChild);
                                successCount++;
                        } catch (Exception e) {
                            log.warn("Failed to save child record at index {} for relation {}: {}",
                                    i, logSafe(relationName), logSafe(e.getMessage()), e);
                                rowErrors.add(JointSubTableSaveResponse.SubTableError.builder()
                                        .rowIndex(i)
                                        .message(e.getMessage())
                                        .data(childData)
                                        .build());
                            }
                        }

                        subTableCounts.put(relationName, successCount);
                        savedRecords.put(relationName, savedChildren);

                        if (!rowErrors.isEmpty()) {
                            subTableErrors.put(relationName, rowErrors);
                            errors.add("Sub-table '" + relationName + "' had " + rowErrors.size() + " errors");
                        }

                        log.info("Saved {} records for relation: {}", successCount, logSafe(relationName));

                    } catch (Exception e) {
                        log.error("Failed to process sub-table {}: {}", logSafe(tableKey), logSafe(e.getMessage()), e);
                        errors.add("Sub-table '" + tableKey + "': " + e.getMessage());
                    }
                }
            }

            long duration = System.currentTimeMillis() - startTime;

            return JointSubTableSaveResponse.builder()
                    .success(errors.isEmpty())
                    .masterId(masterId)
                    .masterRecord(savedMaster)
                    .subTableCounts(subTableCounts)
                    .savedRecords(savedRecords)
                    .subTableErrors(subTableErrors)
                    .errors(errors)
                    .duration(duration)
                    .operationType(opType)
                    .build();

        } catch (Exception e) {
            log.error("Joint save failed for model {}: {}", logSafe(modelCode), logSafe(e.getMessage()), e);
            long duration = System.currentTimeMillis() - startTime;
            errors.add("Master save failed: " + e.getMessage());
            return JointSubTableSaveResponse.failure(errors, duration);
        }
    }

    /**
     * Find relation by name (supports both relation name and target model code)
     */
    private RelationDefinition findRelationByName(ModelDefinition model, String relationName) {
        if (model.getRelations() == null || model.getRelations().isEmpty()) {
            return null;
        }

        // First try exact name match
        for (RelationDefinition relation : model.getRelations()) {
            if (relationName.equals(relation.getName())) {
                return relation;
            }
        }

        // Try target model code match
        for (RelationDefinition relation : model.getRelations()) {
            if (relationName.equals(relation.getTargetModel())) {
                return relation;
            }
        }

        return null;
    }

    /**
     * Delete existing child records for a relation
     */
    private void deleteExistingChildRecords(RelationDefinition relation, String masterId) {
        Long tenantId = getCurrentTenantId();

        if (relation.getRelationType() == RelationDefinition.RelationType.MANY_TO_MANY) {
            // For M2M, delete from join table
            Map<String, Object> conditions = new HashMap<>();
            conditions.put(relation.getSourceField(), masterId);
            conditions.put("tenant_id", tenantId);
            dynamicDataMapper.delete(relation.getJoinTable(), conditions);
            log.debug("Deleted existing M2M relations from {} for master {}",
                    logSafe(relation.getJoinTable()), logSafe(masterId));
        } else if (relation.getRelationType() == RelationDefinition.RelationType.ONE_TO_MANY) {
            // For O2M, delete from target table
            Map<String, Object> conditions = new HashMap<>();
            conditions.put(relation.getTargetField(), masterId);
            conditions.put("tenant_id", tenantId);
            dynamicDataMapper.delete(relation.getTargetTable(), conditions);
            log.debug("Deleted existing child records from {} for master {}",
                    logSafe(relation.getTargetTable()), logSafe(masterId));
        }
    }
}
