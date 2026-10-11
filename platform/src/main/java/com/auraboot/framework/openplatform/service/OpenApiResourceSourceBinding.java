package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.meta.dto.ModelDefinition;
import java.util.LinkedHashMap;
import java.util.Map;

/** Internal provenance of the storage read by an immutable public resource snapshot. */
public record OpenApiResourceSourceBinding(String modelCode, String sourceType, String sourceRef,
                                           String tableName, String primaryKey,
                                           Map<String, Column> columns, String queryFingerprint) {
    public record Column(String columnName, String jsonbColumn, String jsonbPath,
                         String referenceModel, String referenceValueField) { }

    public OpenApiResourceSourceBinding {
        columns = Map.copyOf(columns);
    }

    public static OpenApiResourceSourceBinding capture(OpenApiResourceContract contract, ModelDefinition model) {
        return capture(contract, model, null);
    }

    public static OpenApiResourceSourceBinding capture(OpenApiResourceContract contract, ModelDefinition model,
                                                       String queryFingerprint) {
        Map<String, Column> columns = new LinkedHashMap<>();
        contract.fields().values().forEach(field -> {
            if ("pid".equals(field.source())) {
                columns.put("pid", new Column("pid", null, null, null, null));
                return;
            }
            var source = model.getFields().stream().filter(candidate -> field.source().equals(candidate.getCode()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Published source field is unavailable"));
            var reference = source.getRefTarget();
            columns.put(field.source(), new Column(source.getColumnName() == null || source.getColumnName().isBlank()
                    ? source.getCode() : source.getColumnName(), source.getJsonbColumn(), source.getJsonbPath(),
                    reference == null ? null : reference.getTargetEntity(),
                    reference == null ? null : reference.getValueField() == null ? "pid" : reference.getValueField()));
        });
        return new OpenApiResourceSourceBinding(model.getCode(), model.getSourceType() == null ? "physical" : model.getSourceType(),
                model.getSourceRef(), model.getTableName(), model.getPrimaryKey(), columns, queryFingerprint);
    }
}
