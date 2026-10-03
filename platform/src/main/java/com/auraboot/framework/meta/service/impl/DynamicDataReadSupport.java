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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Applies read permissions and authorized display enrichment without changing CRUD transactions. */
@Slf4j
@RequiredArgsConstructor
final class DynamicDataReadSupport {
    private final MetaModelService metadataService;
    private final DynamicDataMapper dynamicDataMapper;
    private final UserMapper userMapper;
    private final FileService fileService;
    private final DataPermissionEngine dataPermissionEngine;
    private final FieldPermissionService fieldPermissionService;
    private final java.util.function.Supplier<PermissionAuditService> permissionAuditSupplier;
    private final java.util.function.Supplier<PermissionFacade> permissionFacadeSupplier;
    private final java.util.function.Supplier<Long> memberIdSupplier;
    private static final Set<String> AUDIT_USER_DISPLAY_FIELDS = Set.of("created_by", "updated_by");

    private static String logSafe(Object value) { return LogSanitizer.safe(value); }
    List<Map<String, Object>> applyFieldPermissionFilter(String modelCode, List<Map<String, Object>> records) {
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

    List<Map<String, Object>> enrichAuditUsersBeforeFieldPermissionFilter(
            String modelCode,
            List<Map<String, Object>> records,
            List<String> auditUserDisplayFields) {
        enrichAuditUserDisplayFields(records, auditUserDisplayFields);
        return applyFieldPermissionFilter(modelCode, records);
    }

    /**
     * Apply field-level permission filtering to a single record.
     */
    Map<String, Object> applyFieldPermissionFilterSingle(String modelCode, Map<String, Object> record) {
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

    void auditHiddenFieldFiltering(
            String modelCode,
            Long memberId,
            Map<String, Object> record,
            List<String> hiddenFields) {
        if (memberId == null || hiddenFields == null || hiddenFields.isEmpty() || !MetaContext.exists()) {
            return;
        }
        try {
            Long tenantId = MetaContext.getCurrentTenantId();
            permissionAuditSupplier.get().logFieldGovernanceFilter(
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

    Long toLongOrNull(Object value) {
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

    String toNonBlankString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    Long currentMemberIdForFieldPermissions() {
        Long memberId = MetaContext.getCurrentMemberId();
        return memberId != null ? memberId : memberIdSupplier.get();
    }

    List<Map<String, Object>> enrichListRecords(
            String modelCode,
            List<Map<String, Object>> records) {
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

    /**
     * Resolve the visible audit actor columns with one tenant-scoped user query.
     * No query runs when the page does not request an audit field, and unresolved
     * users stay blank rather than falling back to an internal numeric ID.
     */
    void enrichAuditUserDisplayFields(
            List<Map<String, Object>> records,
            List<String> requestedFields) {
        if (records == null || records.isEmpty() || requestedFields == null || requestedFields.isEmpty()) {
            return;
        }

        List<String> fields = requestedFields.stream()
                .filter(AUDIT_USER_DISPLAY_FIELDS::contains)
                .distinct()
                .toList();
        if (fields.isEmpty()) {
            return;
        }

        Set<Long> userIds = new LinkedHashSet<>();
        for (Map<String, Object> record : records) {
            for (String field : fields) {
                Long userId = asLong(record.get(field));
                if (userId != null) {
                    userIds.add(userId);
                }
            }
        }
        if (userIds.isEmpty()) {
            return;
        }

        Long tenantId = MetaContext.getCurrentTenantId();
        Map<Long, String> displayNames = new HashMap<>();
        for (Map<String, Object> user : userMapper.findDisplayNamesByIdsInTenant(tenantId, userIds)) {
            Long userId = asLong(user.get("id"));
            Object displayName = user.get("display_name");
            if (userId != null && displayName != null && !String.valueOf(displayName).isBlank()) {
                displayNames.put(userId, String.valueOf(displayName));
            }
        }

        for (Map<String, Object> record : records) {
            for (String field : fields) {
                String displayName = displayNames.get(asLong(record.get(field)));
                if (displayName != null) {
                    record.put(field + "_display", displayName);
                }
            }
        }
    }

    Long asLong(Object value) {
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
    String[] resolveCanonicalRefTarget(FieldDefinition field) {
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
    String[] resolveEnrichmentTarget(FieldDefinition field) {
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
    boolean isDisplayFieldMasked(Long tenantId, Long userId, String targetModelCode, String displayField) {
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

    record ReferenceReadAccess(boolean allowed, String rowFilter) {
        private static ReferenceReadAccess denied() {
            return new ReferenceReadAccess(false, "");
        }

        private static ReferenceReadAccess allowed(String rowFilter) {
            return new ReferenceReadAccess(true, rowFilter == null ? "" : rowFilter);
        }
    }

    /**
     * Resolve the complete target-side read boundary for a reference lookup.
     *
     * <p>Reading the source model is not authority to enumerate or reveal labels from a different
     * business model. Reference options and display enrichment therefore require target-model
     * RBAC first, then apply that target's row scope. The small system identity map retains the
     * platform's existing tenant-scoped user-name resolution contract; it has no dynamic model
     * permission definition to evaluate.
     */
    ReferenceReadAccess evaluateReferenceReadAccess(
            Long tenantId, Long userId, String targetModelCode) {
        if (tenantId == null || userId == null || !org.springframework.util.StringUtils.hasText(targetModelCode)) {
            return ReferenceReadAccess.denied();
        }
        if (SYSTEM_TABLE_MAP.containsKey(targetModelCode)) {
            return ReferenceReadAccess.allowed("");
        }
        try {
            Long memberId = currentMemberIdForFieldPermissions();
            if (memberId == null
                    || !permissionFacadeSupplier.get().canAction(memberId, targetModelCode, "read")) {
                return ReferenceReadAccess.denied();
            }
            return ReferenceReadAccess.allowed(
                    dataPermissionEngine.buildRowFilter(tenantId, targetModelCode, userId));
        } catch (Exception e) {
            log.error("Reference target authorization failed for model {}; denying lookup",
                    logSafe(targetModelCode), e);
            return ReferenceReadAccess.denied();
        }
    }

    void enrichReferenceDisplayFields(String modelCode, List<Map<String, Object>> records) {
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

            // Collect unique reference IDs
            Set<String> refIds = new java.util.LinkedHashSet<>();
            for (Map<String, Object> record : records) {
                Object val = record.get(columnName);
                if (val != null && !String.valueOf(val).isBlank()) {
                    refIds.add(String.valueOf(val));
                }
            }
            if (refIds.isEmpty()) continue;

            // An empty reference cannot reveal target data. Short-circuit before target RBAC,
            // row-scope and masking lookups; otherwise every nullable reference on every write
            // read-back pays the full authorization query cost despite having nothing to enrich.
            ReferenceReadAccess targetAccess = evaluateReferenceReadAccess(
                    tenantId, userId, targetModelCode);
            if (!targetAccess.allowed()) {
                continue;
            }

            // Sensitive reference: if the display field is masked for THIS user on the target model,
            // do NOT system-resolve a name — leave it to the normal per-user path so masking/field
            // permission is honored (敏感引用不出名/仍按权限). Names (the usual displayField) are not
            // masked, so this only suppresses genuinely sensitive display fields.
            if (displayField != null && isDisplayFieldMasked(tenantId, userId, targetModelCode, displayField)) {
                continue;
            }

            // Batch lookup: query target model for display values
            try {
                // System aliases are backed by fixed platform tables and deliberately have no
                // dynamic model definition. Avoid repeating the same known-negative lookup for
                // every user/organization reference during list enrichment.
                Optional<ModelDefinition> targetModelOpt = resolveSystemTable(targetModelCode) == null
                        ? metadataService.getModelDefinition(targetModelCode)
                        : Optional.empty();
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
                        + buildSoftDeleteClause(targetModelOpt.orElse(null))
                        + (targetAccess.rowFilter().isBlank() ? "" : " " + targetAccess.rowFilter());

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
    void logReferenceEnrichmentFailure(String fieldCode, String modelCode, Exception e) {
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

    String resolveSystemTable(String modelCode) {
        return SYSTEM_TABLE_MAP.get(modelCode);
    }

    // ==================== Atomic counter ====================

    String buildSoftDeleteClause(ModelDefinition modelDefinition) {
        if (modelDefinition != null && modelDefinition.isSoftDelete()) {
            return " AND (deleted_flag = FALSE OR deleted_flag IS NULL)";
        }
        return "";
    }

    String resolveReferenceDisplayColumn(ModelDefinition targetModel, String configuredDisplayField) {
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
    String[] resolveDisplayColumnExpression(
            Optional<ModelDefinition> targetModelOpt, String targetModelCode, String displayField) {
        if (targetModelOpt.isEmpty() && SYSTEM_TABLE_DISPLAY_EXPRESSIONS.containsKey(targetModelCode)) {
            return new String[] { SYSTEM_TABLE_DISPLAY_EXPRESSIONS.get(targetModelCode), "display_value" };
        }
        String col = resolveReferenceDisplayColumn(targetModelOpt.orElse(null), displayField);
        return new String[] { col, col };
    }

}
