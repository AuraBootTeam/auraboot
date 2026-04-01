package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.meta.dto.FieldMaskRule;
import com.auraboot.framework.meta.entity.DataPermissionPolicy;
import com.auraboot.framework.meta.mapper.DataPermissionPolicyMapper;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.auraboot.framework.application.tenant.MetaContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of DataPermissionEngine.
 * Provides row-level filtering (RLS) and column-level masking.
 *
 * <p>Row-level filtering supports multiple scope types:
 * <ul>
 *   <li>ALL - no filtering</li>
 *   <li>SELF - created_by = userId</li>
 *   <li>DEPARTMENT - created_by IN users of same department</li>
 *   <li>DEPARTMENT_TREE - created_by IN users of department + all sub-departments</li>
 *   <li>PROJECT - records within user's project bindings</li>
 *   <li>CUSTOM - custom SQL expression with variable substitution</li>
 * </ul>
 *
 * <p>When multiple ROW policies apply to the same user/model, they are
 * combined with OR logic (union of accessible data).
 *
 * @since 5.1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataPermissionEngineImpl implements DataPermissionEngine {

    private final DataPermissionPolicyMapper policyMapper;

    // ==================== Row-Level Filtering ====================

    @Override
    @Cacheable(value = "dataPermissionRowFilter",
            key = "#tenantId + ':' + #modelCode + ':' + #userId")
    public String buildRowFilter(Long tenantId, String modelCode, Long userId) {
        // Phase 2: findEffectivePolicies joins ab_user_role on member_id.
        // Get memberId from MetaContext; fall back to userId for backward compat (scheduled tasks etc.)
        Long memberId = MetaContext.exists() ? MetaContext.getCurrentMemberId() : null;
        if (memberId == null) {
            // No memberId available — no policies can match via role binding
            return "";
        }
        List<DataPermissionPolicy> policies = policyMapper.findEffectivePolicies(tenantId, modelCode, memberId);

        List<DataPermissionPolicy> rowPolicies = policies.stream()
                .filter(p -> "row".equals(p.getPolicyType()))
                .collect(Collectors.toList());

        if (rowPolicies.isEmpty()) {
            // No row policies defined — allow all (default permissive)
            return "";
        }

        // If any policy grants ALL access, no filter needed
        boolean hasAllAccess = rowPolicies.stream()
                .anyMatch(p -> "all".equals(p.getScopeType()));
        if (hasAllAccess) {
            return "";
        }

        // Build individual filter fragments for each policy
        List<String> fragments = new ArrayList<>();
        for (DataPermissionPolicy policy : rowPolicies) {
            String fragment = buildSingleRowFilterFragment(policy, userId);
            if (fragment != null && !fragment.isBlank()) {
                fragments.add(fragment);
            }
        }

        if (fragments.isEmpty()) {
            return "";
        }

        // Combine multiple fragments with OR logic (most permissive union)
        if (fragments.size() == 1) {
            return "AND " + fragments.get(0);
        }

        return "AND (" + String.join(" OR ", fragments) + ")";
    }

    @Override
    public List<Map<String, Object>> filterRecords(Long tenantId, String modelCode, Long userId,
                                                    List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) {
            return records;
        }

        Long memberId = MetaContext.exists() ? MetaContext.getCurrentMemberId() : null;
        if (memberId == null) {
            return records;
        }
        List<DataPermissionPolicy> policies = policyMapper.findEffectivePolicies(tenantId, modelCode, memberId);
        List<DataPermissionPolicy> rowPolicies = policies.stream()
                .filter(p -> "row".equals(p.getPolicyType()))
                .collect(Collectors.toList());

        // No row policies = allow all
        if (rowPolicies.isEmpty()) {
            return records;
        }

        // ANY policy with ALL scope = allow all
        boolean hasAllAccess = rowPolicies.stream()
                .anyMatch(p -> "all".equals(p.getScopeType()));
        if (hasAllAccess) {
            return records;
        }

        // Filter each record: a record passes if ANY policy allows it (OR logic)
        return records.stream()
                .filter(record -> rowPolicies.stream()
                        .anyMatch(policy -> recordMatchesPolicy(policy, userId, record)))
                .collect(Collectors.toList());
    }

    @Override
    public boolean canAccessRecord(Long tenantId, String modelCode, Long userId,
                                   Map<String, Object> record) {
        if (record == null) {
            return false;
        }

        Long memberId = MetaContext.exists() ? MetaContext.getCurrentMemberId() : null;
        if (memberId == null) {
            return true; // No member context = allow (backward compat)
        }
        List<DataPermissionPolicy> policies = policyMapper.findEffectivePolicies(tenantId, modelCode, memberId);
        List<DataPermissionPolicy> rowPolicies = policies.stream()
                .filter(p -> "row".equals(p.getPolicyType()))
                .collect(Collectors.toList());

        // No policies = allow all
        if (rowPolicies.isEmpty()) {
            return true;
        }

        // ANY policy with ALL scope = allow all
        boolean hasAllAccess = rowPolicies.stream()
                .anyMatch(p -> "all".equals(p.getScopeType()));
        if (hasAllAccess) {
            return true;
        }

        // Record passes if ANY policy allows it (OR logic)
        return rowPolicies.stream()
                .anyMatch(policy -> recordMatchesPolicy(policy, userId, record));
    }

    // ==================== Column-Level Masking ====================

    @Override
    @Cacheable(value = "dataPermissionMaskRules",
            key = "#tenantId + ':' + #modelCode + ':' + #userId")
    public List<FieldMaskRule> getFieldMaskRules(Long tenantId, String modelCode, Long userId) {
        Long memberId = MetaContext.exists() ? MetaContext.getCurrentMemberId() : null;
        if (memberId == null) {
            return Collections.emptyList();
        }
        List<DataPermissionPolicy> policies = policyMapper.findEffectivePolicies(tenantId, modelCode, memberId);

        return policies.stream()
                .filter(p -> "column".equals(p.getPolicyType()))
                .map(p -> FieldMaskRule.builder()
                        .fieldCode(p.getFieldCode())
                        .maskType(p.getMaskType())
                        .maskExpression(p.getMaskExpression())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> applyFieldMasking(
            List<Map<String, Object>> records, List<FieldMaskRule> rules) {
        if (records == null || records.isEmpty() || rules == null || rules.isEmpty()) {
            return records;
        }

        Map<String, FieldMaskRule> ruleMap = rules.stream()
                .collect(Collectors.toMap(FieldMaskRule::getFieldCode, r -> r, (a, b) -> a));

        List<Map<String, Object>> result = new ArrayList<>(records.size());
        for (Map<String, Object> record : records) {
            Map<String, Object> masked = new LinkedHashMap<>(record);
            for (Map.Entry<String, FieldMaskRule> entry : ruleMap.entrySet()) {
                String fieldCode = entry.getKey();
                if (masked.containsKey(fieldCode)) {
                    Object originalValue = masked.get(fieldCode);
                    masked.put(fieldCode, applyMask(originalValue, entry.getValue()));
                }
            }
            result.add(masked);
        }
        return result;
    }

    // ==================== Cache Eviction ====================

    /**
     * Evict all cached row filters and mask rules for a tenant.
     * Should be called when policies or role bindings change.
     */
    @CacheEvict(value = {"dataPermissionRowFilter", "dataPermissionMaskRules"}, allEntries = true)
    public void evictCache() {
        log.info("Evicted all data permission engine caches");
    }

    // ==================== Private: Row Filter SQL Generation ====================

    /**
     * Build a single SQL fragment for one row policy (without the "and" prefix).
     * Returns the raw condition, e.g. "created_by = 123".
     */
    private String buildSingleRowFilterFragment(DataPermissionPolicy policy, Long userId) {
        String scopeType = policy.getScopeType();
        if (scopeType == null) {
            return "";
        }

        switch (scopeType) {
            case "all":
                // Should not reach here (checked earlier), but handle gracefully
                return "";

            case "self":
                return "created_by = " + userId;

            case "department":
                // Use scope_expression as the field to match, default to created_by.
                // The filter checks: records created by anyone in the same department.
                // Since ab_user has no department_id, we use the dynamic org table approach:
                // scope_expression should contain the department field code on the target model,
                // e.g. "pe_emp_dept_id" or fall back to created_by matching via user-department lookup.
                return buildDepartmentFilter(policy, userId, false);

            case "department_tree":
                // Like DEPARTMENT, but includes all sub-departments (recursive).
                return buildDepartmentFilter(policy, userId, true);

            case "project":
                return buildProjectFilter(policy, userId);

            case "custom":
                return buildCustomFilter(policy, userId);

            default:
                log.warn("Unknown scope type '{}' in policy pid={}", scopeType, policy.getPid());
                return "";
        }
    }

    /**
     * Build department-based row filter.
     *
     * <p>The scope_expression field is interpreted as: "departmentFieldCode:departmentModelCode"
     * e.g. "org_emp_dept_id:org_department" or just "org_emp_dept_id" (defaults to org_department).
     *
     * <p>For simple use case, falls back to created_by-based filtering when no expression set.
     */
    private String buildDepartmentFilter(DataPermissionPolicy policy, Long userId, boolean includeSubDepts) {
        String expr = policy.getScopeExpression();
        Long tenantId = policy.getTenantId();

        if (expr == null || expr.isBlank()) {
            // No expression configured: fall back to created_by = userId (safest default)
            log.debug("No scope_expression for DEPARTMENT scope, falling back to SELF filter for policy pid={}",
                    policy.getPid());
            return "created_by = " + userId;
        }

        // Parse expression format: "targetField" or "targetField:deptModelCode:deptParentField"
        String[] parts = expr.split(":");
        String targetField = parts[0].trim();

        // Validate field name to prevent SQL injection
        if (!SqlSafetyUtils.isValidIdentifier(targetField)) {
            log.warn("Invalid target field name in DEPARTMENT scope: {}", targetField);
            return "1=0";
        }

        if (includeSubDepts && parts.length >= 3) {
            // DEPARTMENT_TREE with recursive CTE
            String deptModelCode = parts[1].trim();
            String deptParentField = parts[2].trim();

            if (!SqlSafetyUtils.isValidIdentifier(deptModelCode) || !SqlSafetyUtils.isValidIdentifier(deptParentField)) {
                log.warn("Invalid identifiers in DEPARTMENT_TREE scope expression: {}", expr);
                return "1=0";
            }

            // Build a recursive CTE to find all sub-departments
            // Note: the dynamic table name is "mt_{modelCode}" with hyphens replaced
            String deptTable = SystemFieldConstants.DYNAMIC_TABLE_PREFIX + deptModelCode.replace("-", "_");

            return String.format(
                    "%s IN (" +
                    "WITH RECURSIVE dept_tree(id, lvl) AS (" +
                    "  SELECT id, 1 FROM %s WHERE tenant_id = %d AND id IN (" +
                    "    SELECT %s FROM %s WHERE tenant_id = %d AND created_by = %d" +
                    "  )" +
                    "  UNION ALL" +
                    "  SELECT d.id, dt.lvl + 1 FROM %s d INNER JOIN dept_tree dt ON d.%s = dt.id" +
                    "    WHERE d.tenant_id = %d AND dt.lvl < 10" +
                    ") SELECT id FROM dept_tree" +
                    ")",
                    targetField,
                    deptTable, tenantId, targetField, deptTable, tenantId, userId,
                    deptTable, deptParentField, tenantId
            );
        } else {
            // Simple DEPARTMENT: match records where targetField equals user's department
            // Find user's department by looking at records created by this user
            String deptTable = SystemFieldConstants.DYNAMIC_TABLE_PREFIX + (parts.length >= 2 ? parts[1].trim() : "org_department").replace("-", "_");

            if (parts.length >= 2 && !SqlSafetyUtils.isValidIdentifier(parts[1].trim())) {
                log.warn("Invalid department model code in scope expression: {}", expr);
                return "1=0";
            }

            return String.format(
                    "%s IN (SELECT %s FROM %s WHERE tenant_id = %d AND created_by = %d AND %s IS NOT NULL)",
                    targetField, targetField, deptTable, tenantId, userId, targetField
            );
        }
    }

    /**
     * Build project-based row filter.
     */
    private String buildProjectFilter(DataPermissionPolicy policy, Long userId) {
        String projectField = policy.getScopeExpression();
        if (projectField == null || projectField.isBlank()) {
            projectField = "project_pid";
        }

        if (!SqlSafetyUtils.isValidIdentifier(projectField)) {
            log.warn("Invalid PROJECT scope field name rejected: {}", projectField);
            return "1=0";
        }

        Long tenantId = policy.getTenantId();
        return String.format(
                "%s IN (SELECT project_pid FROM ab_user_project_binding WHERE tenant_id = %d AND user_id = %d)",
                projectField, tenantId, userId);
    }

    /**
     * Build custom SQL expression-based row filter.
     * Supports variable substitution: #userId, #user.id, #tenantId.
     */
    private String buildCustomFilter(DataPermissionPolicy policy, Long userId) {
        String expr = policy.getScopeExpression();
        if (expr == null || expr.isBlank()) {
            return "";
        }

        // Variable substitution
        String resolved = expr
                .replace("#userId", String.valueOf(userId))
                .replace("#user.id", String.valueOf(userId))
                .replace("#tenantId", String.valueOf(policy.getTenantId()));

        // Security: validate as SQL fragment (stricter than containsDangerousPatterns)
        try {
            SqlSafetyUtils.validateSqlFragment(resolved);
        } catch (IllegalArgumentException e) {
            log.warn("Dangerous CUSTOM scope expression rejected: expr='{}', reason='{}'", expr, e.getMessage());
            return "1=0";
        }

        return resolved;
    }

    // ==================== Private: Post-Query Record Matching ====================

    /**
     * Check if a single record matches a given row policy.
     * Used for post-query filtering when SQL-level filtering is not possible.
     */
    private boolean recordMatchesPolicy(DataPermissionPolicy policy, Long userId,
                                        Map<String, Object> record) {
        String scopeType = policy.getScopeType();
        if (scopeType == null || "all".equals(scopeType)) {
            return true;
        }

        switch (scopeType) {
            case "self":
                return matchesSelf(userId, record);

            case "department":
            case "department_tree":
                // For post-query filtering, we compare field values directly.
                // The scope_expression field identifies which record field to check.
                return matchesDepartment(policy, userId, record);

            case "project":
                // Post-query project check would need project binding data.
                // For simplicity, fall back to created_by check.
                return matchesSelf(userId, record);

            case "custom":
                // Custom expressions cannot be reliably evaluated post-query.
                // Fall back to created_by check as safety measure.
                log.debug("CUSTOM scope not supported for post-query filtering, falling back to SELF");
                return matchesSelf(userId, record);

            default:
                log.warn("Unknown scope type for record matching: {}", scopeType);
                return false;
        }
    }

    /**
     * Check if the record was created by the given user.
     */
    private boolean matchesSelf(Long userId, Map<String, Object> record) {
        Object createdBy = record.get("created_by");
        if (createdBy == null) {
            return false;
        }
        if (createdBy instanceof Number) {
            return ((Number) createdBy).longValue() == userId;
        }
        return String.valueOf(userId).equals(String.valueOf(createdBy));
    }

    /**
     * Check if a record's department field matches the user's department scope.
     * For post-query filtering, this is a simplified check based on available record fields.
     */
    private boolean matchesDepartment(DataPermissionPolicy policy, Long userId,
                                      Map<String, Object> record) {
        String expr = policy.getScopeExpression();
        if (expr == null || expr.isBlank()) {
            // No expression = fall back to created_by
            return matchesSelf(userId, record);
        }

        // The first part of the expression is the target field
        String targetField = expr.split(":")[0].trim();
        Object recordDeptValue = record.get(targetField);

        if (recordDeptValue == null) {
            // If the field is not present, fall back to created_by check
            return matchesSelf(userId, record);
        }

        // For post-query filtering, we check if created_by matches
        // (a more complete implementation would load user-department mapping)
        return matchesSelf(userId, record);
    }

    // ==================== Private: Masking ====================

    private Object applyMask(Object value, FieldMaskRule rule) {
        if (value == null) {
            return null;
        }

        String maskType = rule.getMaskType();
        if (maskType == null) {
            return value;
        }

        switch (maskType) {
            case "hide":
                return null;
            case "partial":
                return applyPartialMask(value.toString());
            case "hash":
                return applyHashMask(value.toString());
            case "custom":
                return applyCustomMask(value, rule.getMaskExpression());
            default:
                log.warn("Unknown mask type: {}", maskType);
                return value;
        }
    }

    private String applyPartialMask(String value) {
        if (value.length() <= 2) {
            return "**";
        }
        if (value.length() <= 6) {
            return value.charAt(0) + "***" + value.charAt(value.length() - 1);
        }
        // Show first 3 and last 4 characters
        String prefix = value.substring(0, 3);
        String suffix = value.substring(value.length() - 4);
        return prefix + "****" + suffix;
    }

    private String applyHashMask(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 not available for hash masking", e);
            return "********";
        }
    }

    private Object applyCustomMask(Object value, String expression) {
        if (expression == null || expression.isBlank()) {
            return value;
        }
        // For custom expressions, apply simple pattern-based replacement
        // More complex SpEL evaluation can be added later
        return "***";
    }

    // ==================== Private: Security Utilities ====================

    // isValidIdentifier() and containsDangerousSql() removed — replaced by SqlSafetyUtils
}
