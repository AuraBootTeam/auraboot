package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.decision.dto.DecisionModelFieldDTO;
import com.auraboot.framework.decision.entity.DrtVersionEntity;
import com.auraboot.framework.decision.mapper.DrtVersionMapper;
import com.auraboot.framework.decision.service.DecisionModelFieldService;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.ModelFieldBindingService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Derives the rule-center field catalogue from published model metadata plus validated decision refs.
 */
@Service
@RequiredArgsConstructor
public class DecisionModelFieldServiceImpl implements DecisionModelFieldService {

    private static final Set<String> SUPPORTED_DATA_TYPES = Set.of(
            "string", "text", "integer", "decimal", "boolean", "date", "time",
            "datetime", "duration", "enum", "dict", "user", "role", "group",
            "department", "collection", "object");

    private final DrtVersionMapper versionMapper;
    private final MetaModelMapper metaModelMapper;
    private final ModelFieldBindingService modelFieldBindingService;

    @Override
    public List<DecisionModelFieldDTO> listFields() {
        Long tenantId = requireTenant();
        Map<String, FieldAccumulator> fields = new LinkedHashMap<>();

        collectMetaModelFields(fields);

        for (DrtVersionEntity version : versionMapper.findWithFieldRefs(tenantId)) {
            Map<String, String> dataTypes = new LinkedHashMap<>();
            collectPathDataTypes(version.getContentJson(), dataTypes);

            for (String fieldRef : parseRefs(version.getFieldRefsJson())) {
                FieldRefParts parts = FieldRefParts.parse(fieldRef);
                FieldAccumulator acc = fields.computeIfAbsent(parts.key(), ignored -> new FieldAccumulator(parts));
                acc.refs++;
                acc.decisionCodes.add(version.getDecisionCode());
                acc.dataType = chooseDataType(acc.dataType, dataTypes.get(fieldRef));
            }
        }

        return fields.values().stream()
                .map(FieldAccumulator::toDTO)
                .sorted(Comparator.comparing(DecisionModelFieldDTO::getEntityCode)
                        .thenComparing(DecisionModelFieldDTO::getPath))
                .toList();
    }

    private Long requireTenant() {
        Long tenantId = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        if (tenantId == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Decision model fields not found");
        }
        return tenantId;
    }

    private void collectMetaModelFields(Map<String, FieldAccumulator> fields) {
        for (Model model : metaModelMapper.findCurrentByTenant()) {
            if (model == null || !"published".equalsIgnoreCase(String.valueOf(model.getStatus()))) {
                continue;
            }
            List<MetaFieldDTO> modelFields = modelFieldBindingService.getModelFields(model.getPid());
            String modelLabel = hasText(model.getDisplayName()) ? model.getDisplayName() : model.getCode();
            for (MetaFieldDTO field : modelFields) {
                if (field == null || !Boolean.TRUE.equals(field.getVisible()) || !hasText(field.getCode())) {
                    continue;
                }
                FieldRefParts parts = new FieldRefParts("record", recordDataPath(field.getCode()));
                FieldAccumulator acc = fields.computeIfAbsent(parts.key(), ignored -> new FieldAccumulator(parts));
                acc.dataType = chooseDataType(acc.dataType, field.getDataType());
                String fieldLabel = hasText(field.getDisplayName()) ? field.getDisplayName() : field.getCode();
                acc.label = modelLabel + " / " + fieldLabel;
            }
        }
    }

    private String recordDataPath(String fieldCode) {
        return fieldCode.startsWith("data.") ? fieldCode : "data." + fieldCode;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private List<String> parseRefs(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> refs = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) {
                refs.add(item.asText());
            }
        }
        return refs;
    }

    private void collectPathDataTypes(JsonNode node, Map<String, String> dataTypes) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            if ("path".equals(node.path("type").asText())) {
                String scope = node.path("scope").asText("");
                String path = node.path("path").asText("");
                if (!scope.isBlank() && !path.isBlank()) {
                    dataTypes.put(scope + "." + path, normalizeDataType(node.path("dataType").asText(null)));
                }
            }
            node.properties().forEach(entry -> collectPathDataTypes(entry.getValue(), dataTypes));
        } else if (node.isArray()) {
            node.forEach(child -> collectPathDataTypes(child, dataTypes));
        }
    }

    private String chooseDataType(String existing, String candidate) {
        if (existing != null && !"object".equals(existing)) {
            return existing;
        }
        return normalizeDataType(candidate);
    }

    private String normalizeDataType(String value) {
        if (value == null || value.isBlank()) {
            return "object";
        }
        String normalized = value.trim().toLowerCase();
        return SUPPORTED_DATA_TYPES.contains(normalized) ? normalized : "object";
    }

    private record FieldRefParts(String entityCode, String path) {
        static FieldRefParts parse(String fieldRef) {
            int dot = fieldRef.indexOf('.');
            if (dot <= 0 || dot == fieldRef.length() - 1) {
                return new FieldRefParts("record", fieldRef);
            }
            return new FieldRefParts(fieldRef.substring(0, dot), fieldRef.substring(dot + 1));
        }

        String key() {
            return entityCode + "\u0000" + path;
        }

        String label() {
            int dot = path.lastIndexOf('.');
            return dot >= 0 && dot < path.length() - 1 ? path.substring(dot + 1) : path;
        }
    }

    private static final class FieldAccumulator {
        private final FieldRefParts parts;
        private final Set<String> decisionCodes = new LinkedHashSet<>();
        private int refs;
        private String dataType = "object";
        private String label;

        private FieldAccumulator(FieldRefParts parts) {
            this.parts = parts;
        }

        private DecisionModelFieldDTO toDTO() {
            DecisionModelFieldDTO dto = new DecisionModelFieldDTO();
            dto.setEntityCode(parts.entityCode());
            dto.setPath(parts.path());
            dto.setLabel(label != null ? label : parts.label());
            dto.setDataType(dataType);
            dto.setRefs(refs);
            dto.setMasked(false);
            dto.setDecisionCodes(List.copyOf(decisionCodes));
            return dto;
        }
    }
}
