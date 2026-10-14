package com.auraboot.framework.notification.sourcebinding;

import com.auraboot.framework.meta.constant.DataType;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.entity.payload.FieldRefTargetBean;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
final class ImportedNotificationMetadataValidator {
    Map<String, Map<String, String>> validate(SourceBindingTypes.Binding b, SourceBindingReader reader) {
        Map<String, Map<String, String>> result = new HashMap<>();
        Map<String, Map<String, JsonNode>> approvedFields = new HashMap<>();
        for (var a : b.approvals()) {
            JsonNode graph = reader.metadata(b.tenantId(), a);
            SourceBindingTypes.require(SourceBindingCanonical.hash(graph).equals(a.shapeSha256()));
            JsonNode model = graph.path("model");
            live(model, b.tenantId());
            SourceBindingTypes.require(a.modelCode().equals(model.path("code").textValue()) && "physical".equals(model.path("source_type").textValue()) &&(!model.has("source_ref") || model.path("source_ref").isNull()));
            Set<String> owners = new HashSet<>();
            for (var o : a.owners()) {
                JsonNode p = find(graph.path("plugins"), "pid", o.pid());
                SourceBindingTypes.require(p.path("tenant_id").longValue() == b.tenantId() && !p.path("deleted_flag").booleanValue());
                SourceBindingTypes.require(o.pluginId().equals(p.path("plugin_id").textValue()) && o.namespace().equals(p.path("namespace").textValue()) && o.version().equals(p.path("version").textValue()));
                owners.add(o.pid());
            }
            SourceBindingTypes.require(owners.contains(model.path("plugin_pid").textValue()));
            Map<String, String> fields = new HashMap<>();
            Map<String, JsonNode> fieldFacts = new HashMap<>();
            Set<Long> seen = new HashSet<>();
            for (JsonNode binding : graph.path("bindings")) {
                SourceBindingTypes.require(binding.path("tenant_id").longValue() == b.tenantId() && binding.path("model_id").longValue() == model.path("id").longValue() && binding.has("deleted_flag") && !binding.path("deleted_flag").booleanValue());
                JsonNode field = find(graph.path("fields"), "id", binding.path("field_id").asText());
                live(field, b.tenantId());
                SourceBindingTypes.require(seen.add(field.path("id").longValue()));
                boolean nativeField = a.nativeFieldPids().contains(field.path("pid").textValue());
                SourceBindingTypes.require(nativeField ? binding.path("is_system_binding").asBoolean(false) : owners.contains(field.path("plugin_pid").textValue()));
                if (!nativeField) {
                    JsonNode track = find(graph.path("tracking"), "resource_pid", binding.path("pid").asText());
                    SourceBindingTypes.require(owners.contains(track.path("plugin_pid").textValue()) && track.path("tenant_id").longValue() == b.tenantId() && "model_field_binding".equals(track.path("resource_type").asText()));
                }
                SourceBindingTypes.require(!field.has("data_source_id") || field.path("data_source_id").isNull());
                physicalField(field);
                String alias = binding.path("alias_code").isNull() || binding.path("alias_code").asText().isBlank() ? field.path("code").asText() : binding.path("alias_code").asText();
                SourceBindingTypes.code(alias);
                SourceBindingTypes.require(fields.put(alias, field.path("data_type").asText()) == null);
                fieldFacts.put(alias, field);
            }
            SourceBindingTypes.require(graph.path("fields").size() == seen.size());
            result.put(a.modelCode(), Map.copyOf(fields));
            approvedFields.put(a.modelCode(), Map.copyOf(fieldFacts));
        }
        pidRelation(approvedFields, b.source().entity().modelCode(), b.source().ownerField(), b.owner().modelCode());
        type(result, b.source().entity().modelCode(), b.source().occurredAtField(), DataType.DATETIME.getCode());
        type(result, b.source().entity().modelCode(), b.source().targets().listField(), DataType.JSON.getCode());
        pidRelation(approvedFields, b.source().targets().entity().modelCode(), b.source().targets().ownerField(), b.owner().modelCode());
        if (b.source().targets().deadline() != null) {
            type(result, b.source().targets().entity().modelCode(),
                    b.source().targets().deadline().fieldCode(), DataType.DATETIME.getCode());
        }
        var child = b.source().targets().remainingChild();
        if (child != null) {
            pidRelation(approvedFields, child.entity().modelCode(), child.parentField(), b.source().targets().entity().modelCode());
            type(result, child.entity().modelCode(), child.orderedField(), DataType.DECIMAL.getCode());
            type(result, child.entity().modelCode(), child.submittedField(), DataType.DECIMAL.getCode());
            for (var predicate : child.entity().equals()) {
                type(result, child.entity().modelCode(), predicate.fieldCode(), predicate.value().raw() instanceof Boolean
                        ? DataType.BOOLEAN.getCode() : DataType.STRING.getCode());
            }
        }
        // The platform account is not an imported descriptor model; it retains the original STRING contract.
        type(result, b.principal().entity().modelCode(), b.principal().principalField(), DataType.STRING.getCode());
        pidRelation(approvedFields, b.principal().entity().modelCode(), b.principal().ownerField(), b.owner().modelCode());
        pidRelation(approvedFields, b.principal().entity().modelCode(), b.principal().verificationField(), b.verification().entity().modelCode());
        pidRelation(approvedFields, b.verification().entity().modelCode(), b.verification().ownerField(), b.owner().modelCode());
        for (var entity : List.of(b.source().entity(), b.source().targets().entity(), b.owner(), b.principal().entity(), b.verification().entity())) {
            for (var predicate : entity.equals()) {
                type(result, entity.modelCode(), predicate.fieldCode(), predicate.value().raw() instanceof Boolean
                        ? DataType.BOOLEAN.getCode() : DataType.STRING.getCode());
            }
        }
        b.variableToSourceField().values().forEach(f -> {
            String t = result.get(b.source().entity().modelCode()).get(f);
            SourceBindingTypes.require(DataType.STRING.getCode().equals(t) || DataType.DATETIME.getCode().equals(t));
        });
        return Map.copyOf(result);
    }
    /** Only an already approved, physical relation to the exact descriptor model can store a PID. */
    private void pidRelation(Map<String, Map<String, JsonNode>> fields, String model, String code,
                             String expectedTargetModel) {
        JsonNode field = fields.get(model).get(code);
        SourceBindingTypes.require(field != null);
        if (DataType.STRING.getCode().equals(field.path("data_type").asText())) return;
        SourceBindingTypes.require(DataType.REFERENCE.getCode().equals(field.path("data_type").asText()));
        JsonNode target = field.path("ref_target");
        if (target.isTextual()) target = SourceBindingCanonical.parse(target.textValue());
        SourceBindingTypes.require(target.isObject());
        Set<String> known = new HashSet<>();
        for (var property : FieldRefTargetBean.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(property.getModifiers())) known.add(property.getName());
        }
        known.add("targetModel"); // Canonical FieldRefTargetBean alias.
        // The importer preserves the physical owning relation's cardinality in raw metadata.
        known.add("relationship");
        SourceBindingTypes.require(!target.has("relationship")
                || (target.path("relationship").isTextual()
                    && "many_to_one".equals(target.path("relationship").textValue())));
        target.fieldNames().forEachRemaining(name -> SourceBindingTypes.require(known.contains(name)));
        SourceBindingTypes.require(!(target.has("targetEntity") && target.has("targetModel")));
        FieldRefTargetBean reference = SourceBindingCanonical.refTarget(target);
        SourceBindingTypes.require(expectedTargetModel.equals(reference.getTargetEntity())
                && "pid".equals(reference.getValueField())
                && (reference.getRefType() == null || "entity".equals(reference.getRefType()))
                && reference.getTargetTable() == null && reference.getApiConfig() == null
                && reference.getEnumConfig() == null && reference.getQueryCondition() == null
                && reference.getCascadeConfig() == null && reference.getBidirectional() == null
                && reference.getExtensions() == null);
    }

    private void physicalField(JsonNode field) {
        SourceBindingTypes.require(field.has("feature") && field.has("data_type"));
        SourceBindingTypes.require(!DataType.COMPUTED.getCode().equals(field.path("data_type").asText()));
        JsonNode feature = field.path("feature");
        if (feature.isTextual()) feature = SourceBindingCanonical.parse(feature.textValue());
        SourceBindingTypes.require(feature.isNull() || feature.isObject());
        if (feature.isObject()) {
            Set<String> known = new HashSet<>();
            for (var property : FieldFeatureBean.class.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(property.getModifiers())) {
                    known.add(property.getName());
                }
            }
            feature.fieldNames().forEachRemaining(name -> SourceBindingTypes.require(known.contains(name)));
            FieldFeatureBean bean = SourceBindingCanonical.feature(feature);
            FieldDefinition canonical = FieldDefinition.builder().virtualType(bean.getVirtualType()).computeExpression(bean.getComputeExpression()).computeDependencies(bean.getComputeDependencies()).build();
            SourceBindingTypes.require(!canonical.isVirtual() && canonical.getComputeExpression() == null &&(canonical.getComputeDependencies() == null || canonical.getComputeDependencies().isEmpty()) && bean.getRollUp() == null);
        }
        // FieldDefinitionAssembler flattens at most two nested extension wrappers; no virtual JSON projection is admitted.
        JsonNode extension = field.path("extension");
        if (extension.isTextual()) extension = SourceBindingCanonical.parse(extension.textValue());
        for (int depth = 0; depth<3 && extension.isObject(); depth++) {
            SourceBindingTypes.require(!nonNull(extension, "jsonbColumn") && !nonNull(extension, "jsonbPath"));
            extension = extension.path("extension");
        }
    }
    private boolean nonNull(JsonNode n, String key) {
        return n.has(key) && !n.get(key).isNull();
    }
    private void live(JsonNode row, long tenant) {
        SourceBindingTypes.require(row.has("deleted_flag") && !row.path("deleted_flag").booleanValue() && row.path("tenant_id").longValue() == tenant && row.path("is_current").asBoolean(false));
    }
    private JsonNode find(JsonNode rows, String key, String value) {
        List<JsonNode> matches = new ArrayList<>();
        rows.forEach(n -> {
            if (n.path(key).asText().equals(value)) matches.add(n);
        });
        SourceBindingTypes.require(matches.size() == 1);
        return matches.getFirst();
    }
    private void type(Map<String, Map<String, String>> fields, String model, String field, String type) {
        SourceBindingTypes.require(type.equals(fields.get(model).get(field)));
    }
}
