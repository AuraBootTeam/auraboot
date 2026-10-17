package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.permission.engine.PermissionEvaluator;
import com.auraboot.framework.permission.service.FieldPermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;

/** Exact, read-only reference-label resolution using the active DSL and caller visibility. */
@Service
@RequiredArgsConstructor
public class ModelReferenceLookupService {
    private final MetaModelService models;
    private final DynamicDataService data;
    private final PermissionEvaluator permissions;
    private final FieldPermissionService fieldPermissions;

    public static Map<String, Object> inputSchema() {
        return Map.of("type", "object", "additionalProperties", false,
                "properties", Map.of(
                        "modelCode", Map.of("type", "string", "description", "Current source model code"),
                        "fieldCode", Map.of("type", "string", "description", "Its declared reference field code"),
                        "displayValue", Map.of("type", "string", "description", "Exact human-facing label to resolve, e.g. the customer name")),
                "required", List.of("modelCode", "fieldCode", "displayValue"));
    }

    private void requireVisibleFields(Long member, String model, String... fields) {
        var allowed = fieldPermissions.getFieldPermissions(member, model);
        if (allowed == null || allowed.hiddenFields() == null
                || java.util.Arrays.stream(fields).anyMatch(allowed.hiddenFields()::contains)) {
            throw new AccessDeniedException("Reference field read access denied");
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> resolve(String modelCode, String fieldCode, String displayValue) {
        if (modelCode == null || modelCode.isBlank() || fieldCode == null || fieldCode.isBlank()
                || displayValue == null || displayValue.isBlank() || displayValue.length() > 500) {
            throw new BusinessException("Source model, reference field and exact display value are required");
        }
        Long member = MetaContext.getCurrentMemberId();
        if (member == null) member = MetaContext.getCurrentUserId();
        if (MetaContext.getCurrentTenantId() == null || member == null
                || !permissions.canAction(member, modelCode, "read")) {
            throw new AccessDeniedException("Reference source read access denied");
        }
        requireVisibleFields(member, modelCode, fieldCode);
        var source = models.getModelDefinition(modelCode)
                .orElseThrow(() -> new BusinessException("Reference source model is unavailable"));
        if (source.getFields() == null) throw new BusinessException("Reference source fields are unavailable");
        var field = source.getFields().stream().filter(f -> fieldCode.equals(f.getCode())).findFirst()
                .orElseThrow(() -> new BusinessException("Reference field is unavailable"));
        var target = field.getRefTarget();
        String displayField = target == null ? null : target.getDisplayField() != null ? target.getDisplayField() : target.getTargetField();
        if (!"reference".equals(field.getDataType()) || target == null
                || target.getTargetEntity() == null || target.getTargetEntity().isBlank()
                || !"pid".equals(target.getValueField()) || displayField == null || displayField.isBlank()) {
            throw new BusinessException("Reference must declare its target, stored value and display field");
        }
        if ("sys_user".equals(target.getTargetEntity())) {
            throw new BusinessException("Select system members through the tenant member picker");
        }
        if (!permissions.canAction(member, target.getTargetEntity(), "read")) {
            throw new AccessDeniedException("Reference target read access denied");
        }
        requireVisibleFields(member, target.getTargetEntity(), displayField, target.getValueField());
        var found = data.list(target.getTargetEntity(), DynamicQueryRequest.builder().pageNum(1).pageSize(2)
                .conditions(List.of(QueryCondition.builder().fieldName(displayField)
                        .operator(QueryCondition.Operator.EQ).value(displayValue).build())).build());
        if (found == null || found.getRecords() == null || found.getTotal() == null) {
            throw new IllegalStateException("Reference lookup returned an invalid result");
        }
        if (found.getTotal() > 1 || found.getRecords().size() > 1) {
            throw new BusinessException("Reference label is ambiguous; ask the user to select a specific record");
        }
        if (found.getTotal() != 1 || found.getRecords().size() != 1) {
            throw new BusinessException("Reference record does not exist or is not visible to the current caller");
        }
        Object value = found.getRecords().get(0).get(target.getValueField());
        if (!(value instanceof String stored) || stored.isBlank()) {
            throw new BusinessException("Reference stored value is unavailable");
        }
        return Map.of("success", true, "modelCode", modelCode, "fieldCode", fieldCode,
                "targetModel", target.getTargetEntity(), "storedValue", stored, "displayValue", displayValue);
    }
}
