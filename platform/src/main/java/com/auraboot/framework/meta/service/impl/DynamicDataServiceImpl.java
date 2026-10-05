package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.event.config.TenantAwareTaskDecorator;
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
import com.auraboot.framework.meta.exception.RecordVersionConflictException;
import com.auraboot.framework.meta.util.JsonbFieldHelper;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
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
import com.auraboot.framework.plugin.extension.WorkflowCapability;
import com.auraboot.framework.plugin.pf4j.WorkflowCapabilityRegistry;
import com.auraboot.framework.meta.constant.SystemFieldConstants;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * Replace reference identifiers with the already-authorized display values prepared by
     * {@link #enrichReferenceDisplayFields(String, List)} before writing a user-facing export.
     *
     * <p>A missing display value deliberately becomes {@code null}. Falling back to the raw value
     * would expose an opaque pid when the target record is outside the caller's row scope, its
     * display field is masked, or display enrichment fails closed.
     */
    static List<Map<String, Object>> materializeReferenceDisplayValues(
            List<Map<String, Object>> rows, Set<String> referenceFieldCodes) {
        if (rows == null || rows.isEmpty() || referenceFieldCodes == null
                || referenceFieldCodes.isEmpty()) {
            return rows;
        }
        List<Map<String, Object>> materialized = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            for (String fieldCode : referenceFieldCodes) {
                copy.put(fieldCode, row.get(fieldCode + "_display"));
            }
            materialized.add(copy);
        }
        return materialized;
    }

    // Lazy lookup to break circular dependency: DynamicDataService → AutomationTriggerService → CreateRecordExecutor → DynamicDataService
    private AutomationTriggerService getAutomationTriggerService() {
        return applicationContext.getBean(AutomationTriggerService.class);
    }

    /**
     * Automation handlers execute asynchronously and may immediately re-read the record. Submitting
     * them before the surrounding CRUD transaction commits creates a race where a valid record is
     * reported as missing. Register against the transaction boundary when one exists; direct
     * non-transactional callers retain immediate dispatch.
     */
    private void triggerAutomationAfterCommit(String description, Runnable trigger) {
        // Capture before the request scope ends; AFTER_COMMIT can run after identity cleanup.
        Runnable scopedTrigger = new TenantAwareTaskDecorator().decorate(trigger);
        Runnable safeTrigger = () -> {
            try {
                scopedTrigger.run();
            } catch (Exception e) {
                log.error("Failed to trigger {}: {}", description, logSafe(e.getMessage()), e);
            }
        };
        AfterCommitDispatchSupport.afterCommitOrNow(safeTrigger);
    }

    private PermissionFacade getPermissionFacade() {
        return applicationContext.getBean(PermissionFacade.class);
    }

    private WorkflowCapabilityRegistry getWorkflowCapabilityRegistry() {
        return applicationContext.getBean(WorkflowCapabilityRegistry.class);
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
        return dynamicDataListingSupport().list(modelCode,request);
    }

    /**
     * Apply field-level permission filtering to a list of records.
     * Removes keys that are in the hiddenFields set.
     */
    private DynamicDataReadSupport readSupport() {
        return new DynamicDataReadSupport(metadataService, dynamicDataMapper, userMapper, fileService,
                dataPermissionEngine, fieldPermissionService, this::getPermissionAuditService,
                this::getPermissionFacade, this::resolveCurrentTenantMemberId);
    }

    private List<Map<String, Object>> applyFieldPermissionFilter(String modelCode, List<Map<String, Object>> records) {
        return readSupport().applyFieldPermissionFilter(modelCode, records);
    }

    private List<Map<String, Object>> enrichAuditUsersBeforeFieldPermissionFilter(
            String modelCode,
            List<Map<String, Object>> records,
            List<String> auditUserDisplayFields) {
        return readSupport().enrichAuditUsersBeforeFieldPermissionFilter(modelCode, records, auditUserDisplayFields);
    }

    private Map<String, Object> applyFieldPermissionFilterSingle(String modelCode, Map<String, Object> record) {
        return readSupport().applyFieldPermissionFilterSingle(modelCode, record);
    }

    private Long currentMemberIdForFieldPermissions() {
        return readSupport().currentMemberIdForFieldPermissions();
    }

    private List<Map<String, Object>> enrichListRecords(
            String modelCode,
            List<Map<String, Object>> records) {
        return readSupport().enrichListRecords(modelCode, records);
    }

    private void enrichAuditUserDisplayFields(
            List<Map<String, Object>> records,
            List<String> requestedFields) {
        readSupport().enrichAuditUserDisplayFields(records, requestedFields);
    }

    private String[] resolveEnrichmentTarget(FieldDefinition field) {
        return readSupport().resolveEnrichmentTarget(field);
    }

    private DynamicDataReadSupport.ReferenceReadAccess evaluateReferenceReadAccess(
            Long tenantId, Long userId, String targetModelCode) {
        return readSupport().evaluateReferenceReadAccess(tenantId, userId, targetModelCode);
    }

    private void enrichReferenceDisplayFields(String modelCode, List<Map<String, Object>> records) {
        readSupport().enrichReferenceDisplayFields(modelCode, records);
    }

    private String resolveSystemTable(String modelCode) {
        return readSupport().resolveSystemTable(modelCode);
    }

    private String buildSoftDeleteClause(ModelDefinition modelDefinition) {
        return readSupport().buildSoftDeleteClause(modelDefinition);
    }

    private String[] resolveDisplayColumnExpression(
            Optional<ModelDefinition> targetModelOpt, String targetModelCode, String displayField) {
        return readSupport().resolveDisplayColumnExpression(targetModelOpt, targetModelCode, displayField);
    }

    private static final Set<String> NUMERIC_DATA_TYPES = Set.of(
            "integer", "int", "long", "bigint", "decimal", "numeric", "float", "double");

    @Override
    @Transactional
    public boolean compareAndSet(String modelCode,
                                 String recordId,
                                 String fieldCode,
                                 Object expectedValue,
                                 Object nextValue) {
        Map<String, Object> nextValues = new LinkedHashMap<>();
        nextValues.put(fieldCode, nextValue);
        return compareAndSet(modelCode, recordId, fieldCode, expectedValue, nextValues);
    }

    @Override
    @Transactional
    public boolean compareAndSet(String modelCode,
                                 String recordId,
                                 String fieldCode,
                                 Object expectedValue,
                                 Map<String, Object> nextValues) {
        validateModelCode(modelCode);
        assertWritable(modelCode);
        if (recordId == null || recordId.isBlank()) {
            throw new MetaServiceException("Record ID cannot be null or empty");
        }
        if (fieldCode == null || fieldCode.isBlank()) {
            throw new MetaServiceException("Field code cannot be null or empty");
        }

        ModelDefinition model = getModelDefinition(modelCode);
        ModelMutationGuard.assertMutable(model, "updated");
        FieldDefinition compareField = DynamicDataValueMapper.findFieldDefinition(model, fieldCode);
        if (compareField.isPrimaryKey() || compareField.isJsonbVirtual() || compareField.isVirtual()) {
            throw new MetaServiceException(
                    "compareAndSet requires a writable stored field: " + fieldCode);
        }
        if (compareField.isImmutable() || compareField.getImmutableWhen() != null) {
            throw new MetaServiceException(
                    "compareAndSet cannot bypass immutable field rules: " + fieldCode);
        }
        if (nextValues == null || nextValues.isEmpty()) {
            throw new MetaServiceException("compareAndSet next values cannot be empty");
        }
        Map<String, Object> data = new LinkedHashMap<>(nextValues);
        stripNonWritableFields(modelCode, data);
        if (data.size() != nextValues.size()) {
            throw new MetaServiceException("compareAndSet contains a non-writable field");
        }
        for (String nextFieldCode : data.keySet()) {
            FieldDefinition nextField = DynamicDataValueMapper.findFieldDefinition(model, nextFieldCode);
            if (nextField.isPrimaryKey() || nextField.isJsonbVirtual() || nextField.isVirtual()
                    || nextField.isImmutable() || nextField.getImmutableWhen() != null) {
                throw new MetaServiceException(
                        "compareAndSet requires writable mutable stored fields: " + nextFieldCode);
            }
        }
        // CAS never needs a pre-read: the expected value and every scope predicate are part of
        // the UPDATE itself. Exact-command provenance can be checked conservatively from the
        // field definition; conditional immutability was rejected above because it needs a row.
        FieldWriterGuard.assertFieldsAllowed(model, new ArrayList<>(data.keySet()));

        payloadTemporalNormalizer.normalize(data, model);
        validationService.validateAndThrow(model, data, ValidationContext.UPDATE);
        data = convertDataTypes(model, data);

        Map<String, Object> expectedData = new LinkedHashMap<>();
        expectedData.put(fieldCode, expectedValue);
        payloadTemporalNormalizer.normalize(expectedData, model);
        expectedData = convertDataTypes(model, expectedData);

        Map<String, Object> enriched = new HashMap<>(data);
        enriched.put("updated_at", java.sql.Timestamp.from(java.time.Instant.now()));
        enriched.put("updated_by", getCurrentUserId());
        filterVirtualFields(model, enriched);
        Map<String, Object> columnData = toColumnData(model, enriched);

        FieldDefinition primaryKey = metadataService.getPrimaryKeyField(modelCode);
        String primaryKeyColumn = primaryKey.getColumnName() != null
                ? primaryKey.getColumnName()
                : primaryKey.getCode();
        String compareColumn = compareField.getColumnName() != null
                ? compareField.getColumnName()
                : compareField.getCode();
        Long planExpectedVersion = MetaContext.getCommandExpectedVersion(modelCode, recordId);
        int updated = executeScopedUpdate(
                model,
                modelCode,
                primaryKeyColumn,
                recordId,
                columnData,
                JsonbFieldHelper.getJsonbHostColumns(model),
                planExpectedVersion,
                compareColumn,
                expectedData.get(fieldCode));
        if (updated > 1) {
            throw new MetaServiceException("compareAndSet updated more than one record");
        }
        if (updated == 1 && planExpectedVersion != null) {
            MetaContext.advanceCommandExpectedVersion(modelCode, recordId);
        }
        return updated == 1;
    }

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
        ModelMutationGuard.assertMutable(model, "incremented");
        FieldWriterGuard.assertFieldWriteAllowed(model, counterCode);
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

    @Override
    public PaginationResult<Map<String, Object>> listByQueryCode(String queryCode, DynamicQueryRequest request) {
        return dynamicDataListingSupport().listByQueryCode(queryCode,request);
    }

    private String resolveViewNamedQueryCode(String modelCode) {
        return dynamicDataListingSupport().resolveViewNamedQueryCode(modelCode);
    }

    /**
     * List data by delegating to NamedQuery with the given code.
     * Passes through filter conditions and sort fields from the dynamic query request.
     */
    private PaginationResult<Map<String, Object>> listFromNamedQuery(String queryCode, DynamicQueryRequest request) {
        return dynamicDataListingSupport().listFromNamedQuery(queryCode,request);
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
                throw new com.auraboot.framework.meta.exception.MetaRecordNotFoundException(modelCode, recordId);
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
            throw new com.auraboot.framework.meta.exception.MetaRecordNotFoundException(modelCode, recordId);
        }

        Map<String, Object> record = records.get(0);

        // Read-shape contract: json/jsonb fields leave as JSON strings, never PGobject.
        JsonbFieldHelper.normalizeJsonReadValues(model, record);

        boolean commandPermitInForce = MetaContext.hasCommandPermitScopeFor(modelCode);
        if (commandPermitInForce
                && !CommandPermitDataAccess.permitsRecord(modelCode, record, getCurrentUserId())) {
            throw new AccessDeniedException("Access denied: command permit scope does not include this record");
        }

        if (!commandPermitInForce) {
            // Apply the Rule Center-backed permission pipeline before the
            // legacy row-level gate. A Rule Center DENY is responsible for
            // producing the permission audit/trace row; if the legacy row ACL
            // runs first, the request can fail with no explainability trail.
            // Field-level masking still happens afterwards so the evaluator
            // sees the original record shape.
            try {
                enforceRuleCenterRecordPermission(modelCode, recordId, record);
            } catch (AccessDeniedException denied) {
                throw denied;
            } catch (MetaServiceException e) {
                throw e;
            } catch (Exception e) {
                log.error("Failed to evaluate Rule Center permission for model {} record {} — failing closed for security",
                        logSafe(modelCode), logSafe(recordId), e);
                throw new MetaServiceException(
                        "Rule Center permission evaluation failed for model: " + modelCode, e);
            }
        }

        if (!commandPermitInForce) {
            // Apply row-level access check for single record — fail-secure: any
            // non-MetaServiceException must surface as a 5xx, not be swallowed.
            // Mirrors the list() pattern at lines 173 and 185. This remains an
            // additional legacy guard after the Rule Center permission pipeline
            // has either allowed the record or produced a DENY trace.
            try {
                Long userId = getCurrentUserId();
                if (!dataPermissionEngine.canAccessRecord(tenantId, modelCode, userId, record)) {
                    throw new AccessDeniedException("Access denied: you do not have permission to view this record");
                }
            } catch (AccessDeniedException denied) {
                throw denied;
            } catch (MetaServiceException e) {
                throw e;
            } catch (Exception e) {
                log.error("Failed to evaluate row-level access for model {} record {} — failing closed for security",
                        logSafe(modelCode), logSafe(recordId), e);
                throw new MetaServiceException(
                        "Data permission evaluation failed for model: " + modelCode, e);
            }
        }

        if (!commandPermitInForce) {
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

        if (!commandPermitInForce) {
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

        if (!commandPermitInForce) {
            // Apply field-level permission filtering — remove hidden fields
            record = applyFieldPermissionFilterSingle(modelCode, record);
        }

        if (!commandPermitInForce) {
            // Resolve reference display names (same as list) so the detail page shows names, not pids.
            // Internal command reads intentionally retain the canonical stored shape.
            List<Map<String, Object>> single = new java.util.ArrayList<>(1);
            single.add(record);
            enrichReferenceDisplayFields(modelCode, single);
            record = single.get(0);
        }

        return record;
    }

    private void enforceRuleCenterRecordPermission(String modelCode, String recordId, Map<String, Object> record) {
        RuleCenterRecordReadAuthorization.requireReadable(modelCode, record,
                this::currentMemberIdForRuleCenterPermission, this::getPermissionFacade);
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
        ModelDefinition model = getModelDefinition(modelCode);
        ModelMutationGuard.assertCreateAllowed(model);
        FieldWriterGuard.assertCreateAllowed(model, data);

        // Field-level write permission (gap #1): strip fields the current user may not write
        stripNonWritableFields(modelCode, data);

        logOperation("create", modelCode, data.keySet());

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
                MetaContext.runWithCommandPermitScope("ALL", () -> getById(modelCode, recordIdValue));
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
        Map<String, Object> automationRecord = new LinkedHashMap<>(createdRecord);
        triggerAutomationAfterCommit(
                "automations for create: model=" + logSafe(modelCode)
                        + ", id=" + logSafe(recordIdValue),
                () -> getAutomationTriggerService()
                        .onRecordCreate(modelCode, recordIdValue, automationRecord));

        // Optional product lifecycle hook. The platform remains valid without a workflow provider.
        WorkflowCapabilityRegistry workflowCapabilities = getWorkflowCapabilityRegistry();
        if (workflowCapabilities.available("record.created")) {
            try {
                workflowCapabilities.execute("record.created", new WorkflowCapability.WorkflowRequest(
                        MetaContext.getCurrentTenantId(), MetaContext.getCurrentUserId(), Map.of(
                        "modelCode", modelCode,
                        "recordPid", recordIdValue,
                        "record", automationRecord)));
            } catch (Exception e) {
                log.error("Failed to dispatch optional record lifecycle capability: model={}, id={}: {}",
                        logSafe(modelCode), logSafe(recordIdValue), logSafe(e.getMessage()), e);
            }
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
        if (MetaContext.hasCommandPermitScopeFor(modelCode)) {
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
        return DynamicDataValueMapper.convertFieldValue(field, value);
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
            ModelMutationGuard.assertMutable(model, "updated");

            // Check if record exists
            Map<String, Object> existingRecord = getById(modelCode, recordId);
            if (existingRecord == null) {
                throw new MetaServiceException("Record not found with ID: " + recordId);
            }
            FieldWriterGuard.assertUpdateAllowed(model, inputData, existingRecord);

            // A field the caller may not write was dropped above. Now that the stored row is in
            // hand we can tell whether that was harmless (they sent back the value already there)
            // or a real refusal that must not be delivered silently.
            assertNoDeniedFieldWrites(modelCode, inputData, strippedNonWritable, existingRecord);

            // Normalize temporal string values to typed objects (LocalDate/Instant) before validation
            payloadTemporalNormalizer.normalize(data, model);
            // 使用验证服务的严格模式进行验证
            // 验证失败会抛出异常并触发事务回滚
            // Uniqueness validation needs the current public identity so the existing row does
            // not conflict with itself when a handler resubmits an unchanged unique field.
            Map<String, Object> validationData = new LinkedHashMap<>(data);
            Object existingInternalId = existingRecord.get("id");
            Object existingPid = existingRecord.get("pid");
            if (existingInternalId != null) {
                validationData.put("id", existingInternalId);
            } else if (existingPid != null) {
                validationData.put("pid", existingPid);
            } else {
                validationData.put("pid", recordId);
            }
            validationService.validateAndThrow(model, validationData, ValidationContext.UPDATE);
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

            // D5: a command write compares against the version the server loaded at the
            // authorization boundary. Direct non-command callers retain the legacy payload
            // contract for compatibility.
            Object clientExpectedVersion = enrichedData.remove("_expectedVersion");
            Long planExpectedVersion = MetaContext.getCommandExpectedVersion(modelCode, recordId);
            Object expectedVersion =
                    planExpectedVersion != null ? planExpectedVersion : clientExpectedVersion;

            // Use JSONB-merge-aware toColumnData for UPDATE to preserve unmodified JSONB keys
            Map<String, Object> columnData = toColumnDataForUpdate(model, enrichedData, existingRecord);
            FieldDefinition primaryKey = metadataService.getPrimaryKeyField(modelCode);
            String primaryKeyColumn = primaryKey.getColumnName() != null
                    ? primaryKey.getColumnName()
                    : primaryKey.getCode();

            // Execute update with tenant and DataScope guards in the write SQL itself.
            Set<String> jsonbColumns = JsonbFieldHelper.getJsonbHostColumns(model);
            int result = executeScopedUpdate(
                    model, modelCode, primaryKeyColumn, recordId, columnData, jsonbColumns, expectedVersion);
            if (result <= 0) {
                if (expectedVersion != null) {
                    throw new RecordVersionConflictException(
                            "Update failed: version conflict (expected version " + expectedVersion + ")");
                }
                throw new MetaServiceException("Failed to update record");
            }
            if (planExpectedVersion != null) {
                MetaContext.advanceCommandExpectedVersion(modelCode, recordId);
            }

            // Materialize computed fields after update
            virtualFieldEngine.materialize(modelCode, recordId, changedFields);

            // Record change log. Same platform-internal read-back as create():
            // the caller's read permissions must not gate the row we just wrote.
            // The pre-update read of existingRecord above deliberately keeps the
            // permission projection — you may not modify a record you cannot see.
            Map<String, Object> updatedRecord =
                    MetaContext.runWithCommandPermitScope("ALL", () -> getById(modelCode, recordId));
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
            Map<String, Object> automationBefore = new LinkedHashMap<>(existingRecord);
            Map<String, Object> automationAfter = new LinkedHashMap<>(updatedRecord);
            triggerAutomationAfterCommit(
                    "automations for update: model=" + logSafe(modelCode)
                            + ", id=" + logSafe(recordId),
                    () -> getAutomationTriggerService().onRecordUpdate(
                            modelCode, recordId, automationBefore, automationAfter));

            return updatedRecord;

        } catch (RecordVersionConflictException e) {
            // Pass the wire-stable 409/40900 contract through unwrapped: mobile
            // offline replay keys on this status to branch into conflict resolution.
            throw e;
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
        ModelMutationGuard.assertDeleteAllowed(model);

        // Get record before deletion for change tracking
        Map<String, Object> existingRecord = getById(modelCode, recordId);
        Long planExpectedVersion = MetaContext.getCommandExpectedVersion(modelCode, recordId);

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
            result = executeScopedUpdate(
                    model, modelCode, primaryKeyColumn, recordId, updateData, Set.of(), planExpectedVersion);
        } else {
            // Hard delete: DELETE FROM (default behavior)
            result = executeScopedDelete(
                    model, modelCode, primaryKeyColumn, recordId, planExpectedVersion);
        }
        if (result <= 0) {
            if (planExpectedVersion != null) {
                throw new com.auraboot.framework.exception.ConflictException(
                        "Delete refused: target changed after command authorization");
            }
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
        return dynamicScopedWriteSupport().executeScopedUpdate(model,modelCode,primaryKeyColumn,recordId,columnData,jsonbColumns,expectedVersion);
    }

    private int executeScopedUpdate(
            ModelDefinition model,
            String modelCode,
            String primaryKeyColumn,
            String recordId,
            Map<String, Object> columnData,
            Set<String> jsonbColumns,
            Object expectedVersion,
            String compareColumn,
            Object compareValue) {
        return dynamicScopedWriteSupport().executeScopedUpdate(model,modelCode,primaryKeyColumn,recordId,columnData,jsonbColumns,expectedVersion,compareColumn,compareValue);
    }

    private int executeScopedDelete(
            ModelDefinition model,
            String modelCode,
            String primaryKeyColumn,
            String recordId,
            Long expectedVersion) {
        return dynamicScopedWriteSupport().executeScopedDelete(model,modelCode,primaryKeyColumn,recordId,expectedVersion);
    }

    /**
     * Pin a write to the aggregate root the command was authorized against.
     *
     * <p>This is not a policy decision: it executes the aggregate boundary the entry already fixed.
     * A command authorized for Q1001 must not reach Q2002's rows precisely on the paths that inherit
     * its permit plan.</p>
     *
     * <p>Inert unless both an aggregate scope is open and the model declares a binding, so models
     * opt in one at a time rather than the whole platform changing behaviour at once.</p>
     */
    // package-private + static: the safety property (an open aggregate scope pins every guarded
    // write, including under an authoritative command plan) is directly tested, and the guard depends on no
    // instance state.
    static void appendAggregateBindingGuard(StringBuilder sql, Map<String, Object> params, ModelDefinition model) {
        DynamicScopedWriteSupport.appendAggregateBindingGuard(sql,params,model);
    }

    /**
     * A binding names a <em>field code</em>; SQL needs the physical column. Falls back to the code
     * when the model declares no explicit column, which is the common case.
     */
    private static String resolveBindingColumn(ModelDefinition model, String fieldCode) {
        return DynamicScopedWriteSupport.resolveBindingColumn(model,fieldCode);
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
        dynamicScopedWriteSupport().appendScopedWriteGuards(sql,tenantId,modelCode,userId,operation);
    }

    /**
     * The row filter for a guarded write. A command plan executes its authoritative grade directly:
     * ALL contributes no predicate and SELF contributes the owner predicate. Without a command plan,
     * direct callers retain the existing engine path.
     */
    private String resolveWriteRowFilter(Long tenantId, String modelCode, Long userId) {
        return dynamicScopedWriteSupport().resolveWriteRowFilter(tenantId,modelCode,userId);
    }

    @Override
    public DynamicBatchResponse batchCreate(String modelCode, List<Map<String, Object>> dataList) {
        return dynamicDataBatchSupport().batchCreate(modelCode,dataList);
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
        return dynamicDataBatchSupport().bulkCreate(modelCode,dataList);
    }

    private boolean isRecordNotFound(MetaServiceException e) {
        return dynamicDataBatchSupport().isRecordNotFound(e);
    }

    @Override
    @Transactional
    public DynamicBatchResponse batchUpdate(String modelCode, List<Map<String, Object>> dataList) {
        return dynamicDataBatchSupport().batchUpdate(modelCode,dataList);
    }

    @Override
    @Transactional
    public void batchDelete(String modelCode, List<String> recordIds) {
        dynamicDataBatchSupport().batchDelete(modelCode,recordIds);
    }

    private void appendScopedBulkFilter(StringBuilder sql, String filter) {
        dynamicScopedWriteSupport().appendScopedBulkFilter(sql,filter);
    }

    private void rejectStatementInjectionMarkers(String filter) {
        dynamicScopedWriteSupport().rejectStatementInjectionMarkers(filter);
    }

    // ==================== Custom Query ====================

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> executeCustomQuery(String modelCode, String queryName, Map<String, Object> queryParams) {
        return dynamicDataListingSupport().executeCustomQuery(modelCode,queryName,queryParams);
    }

    // ==================== Aggregate ====================

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> aggregate(String modelCode, AggregateRequest aggregateRequest) {
        return dynamicDataListingSupport().aggregate(modelCode,aggregateRequest);
    }

    // ==================== Stats ====================

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getStats(String modelCode, Map<String, Object> statsParams) {
        return dynamicDataListingSupport().getStats(modelCode,statsParams);
    }

    private DynamicDataRelationSupport relationSupport() {
        return new DynamicDataRelationSupport(dynamicDataMapper, dataPermissionEngine,
                dataDomainService, this::getModelDefinition);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getRelationData(String modelCode, String recordId,
                                                    String relationName, Map<String, Object> queryParams) {
        validateModelCode(modelCode);
        logOperation("getRelationData", modelCode, relationName);
        RelationDefinition relation = DynamicDataValueMapper.findRelation(getModelDefinition(modelCode), relationName);
        Long tenantId = getCurrentTenantId();
        // Source visibility must be checked before any relation query.
        getById(modelCode, recordId);
        return relationSupport().getRelationData(relation, recordId, queryParams, tenantId, getCurrentUserId());
    }

    @Override
    @Transactional
    public RelationOperationResult createRelations(String modelCode, String recordId,
                                                    String relationName, List<String> targetRecordIds) {
        validateModelCode(modelCode);
        logOperation("createRelations", modelCode, relationName);
        RelationDefinition relation = DynamicDataValueMapper.findRelation(getModelDefinition(modelCode), relationName);
        return relationSupport().createRelations(relation, recordId, targetRecordIds, getCurrentTenantId());
    }

    @Override
    @Transactional
    public RelationOperationResult removeRelations(String modelCode, String recordId,
                                                    String relationName, List<String> targetRecordIds) {
        validateModelCode(modelCode);
        logOperation("removeRelations", modelCode, relationName);
        RelationDefinition relation = DynamicDataValueMapper.findRelation(getModelDefinition(modelCode), relationName);
        return relationSupport().removeRelations(relation, recordId, targetRecordIds, getCurrentTenantId());
    }

    // ==================== Validation ====================

    @Override
    public ValidationResult validate(String modelCode, Map<String, Object> data, ValidationContext validationContext) {
        validateModelCode(modelCode);

        ModelDefinition model = getModelDefinition(modelCode);
        return validationService.validateData(model, data, validationContext);
    }

    // ==================== Field Options ====================







    @Override
    @Transactional(readOnly = true)
    public List<FieldOption> getFieldOptions(String modelCode, String fieldCode, FieldOptionRequest optionRequest) {
        return referenceOptionsQuery().getFieldOptions(modelCode,fieldCode,optionRequest);
    }

    // ==================== Export ====================

    @Override
    @Transactional(readOnly = true)
    public ExportResult exportData(String modelCode, DataExportRequest exportRequest) {
        return dynamicDataTransferSupport().exportData(modelCode,exportRequest);
    }

    // ==================== Import ====================

    @Override
    @Transactional
    public ImportResult importData(String modelCode, DataImportRequest importRequest) {
        return dynamicDataTransferSupport().importData(modelCode,importRequest);
    }

    // ==================== Custom Action ====================

    @Override
    @Transactional
    public ActionExecutionResult executeCustomAction(String modelCode, String actionName, Map<String, Object> actionParams) {
        return dynamicDataTransferSupport().executeCustomAction(modelCode,actionName,actionParams);
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
        // Metadata mutations already evict modelDefinitions. Bypassing that cache here used to
        // evict and rebuild the complete model definition before every business-row write. A
        // multi-step command therefore reloaded fields and relations for each mutation.
        ModelDefinition def = metadataService.getModelDefinition(modelCode).orElse(null);
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
        return DynamicDataValueMapper.toColumnData(model, data);
    }

    private Map<String, Object> toColumnDataForUpdate(ModelDefinition model, Map<String, Object> data, Map<String, Object> existingRecord) {
        return DynamicDataValueMapper.toColumnDataForUpdate(model, data, existingRecord);
    }

    private DynamicDataFileCodec fileCodec() {
        return dynamicDataTransferSupport().fileCodec();
    }

    private Path exportAsExcel(List<Map<String, Object>> data, List<String> fields,
                              Map<String, String> labels, String name, Boolean header) throws IOException {
        return dynamicDataTransferSupport().exportAsExcel(data,fields,labels,name,header);
    }

    private Path exportAsCsv(List<Map<String, Object>> data, List<String> fields,
                            Map<String, String> labels, String name, Boolean header) throws IOException {
        return dynamicDataTransferSupport().exportAsCsv(data,fields,labels,name,header);
    }

    private Path exportAsJson(List<Map<String, Object>> data, List<String> fields,
                             Map<String, String> labels, String name) throws IOException {
        return dynamicDataTransferSupport().exportAsJson(data,fields,labels,name);
    }

    private List<Map<String, Object>> parseJsonImport(Path path) throws IOException {
        return dynamicDataTransferSupport().parseJsonImport(path);
    }

    private List<Map<String, Object>> parseCsvImport(Path path, Boolean skipFirstRow) throws IOException {
        return dynamicDataTransferSupport().parseCsvImport(path,skipFirstRow);
    }

    private Map<String, Object> applyFieldMapping(Map<String, Object> row, Map<String, String> mapping) {
        return dynamicDataTransferSupport().applyFieldMapping(row,mapping);
    }

    private List<SortField> mapSortFields(ModelDefinition model, List<SortField> sortFields) {
        return dynamicDataListingSupport().mapSortFields(model,sortFields);
    }

    // ==================== Joint Sub-Table Save ====================

    @Override
    @Transactional
    public JointSubTableSaveResponse saveWithRelations(String modelCode, JointSubTableSaveRequest request) {
        return dynamicJointSaveSupport().saveWithRelations(modelCode,request);
    }

    /**
     * Find relation by name (supports both relation name and target model code)
     */
    private RelationDefinition findRelationByName(ModelDefinition model, String relationName) {
        return dynamicJointSaveSupport().findRelationByName(model,relationName);
    }

    /**
     * Delete existing child records for a relation
     */
    private void deleteExistingChildRecords(RelationDefinition relation, String masterId) {
        dynamicJointSaveSupport().deleteExistingChildRecords(relation,masterId);
    }

    private DynamicReferenceOptionsQuery referenceOptionsQuery() {
        return new DynamicReferenceOptionsQuery(dynamicDataMapper, metadataService, readSupport(), this::getModelDefinition);
    }

    private DynamicScopedWriteSupport dynamicScopedWriteSupport() {
        return new DynamicScopedWriteSupport(dynamicDataMapper, dataPermissionEngine, dataDomainService);
    }

    private DynamicDataTransferSupport dynamicDataTransferSupport() {
        return new DynamicDataTransferSupport(queryBuilderService, dynamicDataMapper, objectMapper, dataPermissionEngine, fieldMaskService, dataDomainService, fieldPermissionService, DynamicDataServiceImpl::buildFieldLabelMap, DynamicDataServiceImpl::materializeReferenceDisplayValues, this::applyFieldPermissionFilter, this::currentMemberIdForFieldPermissions, this::resolveEnrichmentTarget, this::enrichReferenceDisplayFields, this::appendScopedBulkFilter, this::getModelDefinition, this::assertWritable, this::toColumnData, this::mapSortFields);
    }

    private DynamicJointSaveSupport dynamicJointSaveSupport() {
        return new DynamicJointSaveSupport(metadataService, dynamicDataMapper, this::create, this::update, this::getModelDefinition, this::assertWritable);
    }

    private DynamicDataBatchSupport dynamicDataBatchSupport() {
        return new DynamicDataBatchSupport(metadataService, validationService, typeSystemManager, dynamicDataMapper, dataPermissionEngine, dataDomainService, payloadTemporalNormalizer, this::getById, this::create, this::convertDataTypes, this::filterVirtualFields, this::stripNonWritableFields, this::ensureTableExists, this::update, this::appendScopedBulkFilter, this::getModelDefinition, this::assertWritable, this::toColumnData);
    }

    private DynamicDataListingSupport dynamicDataListingSupport() {
        return new DynamicDataListingSupport(queryBuilderService, namedQueryService, secureSqlRewriter, dynamicDataMapper, dataPermissionEngine, fieldMaskService, dataDomainService, metaModelMapper, executorRegistry, this::enrichAuditUsersBeforeFieldPermissionFilter, this::enrichListRecords, this::enrichAuditUserDisplayFields, this::getModelDefinition);
    }
}
