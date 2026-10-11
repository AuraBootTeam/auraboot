package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import com.auraboot.framework.meta.service.ModelMutationObserver;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.plugin.extension.integration.ReliableIntegrationAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/** Trusted source capture for published model events; no caller-projected data is reused. */
@Component
@RequiredArgsConstructor
public class OpenApiModelEventObserver implements ModelMutationObserver {
    private final OpenApiPublicationService publications;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ReliableIntegrationAccessor integration;

    @Override
    public void observe(ModelDefinition model, String pid, Operation operation) {
        long tenant = MetaContext.getCurrentTenantId();
        var contracts = publications.publishedContracts(tenant).stream()
                .filter(contract -> contract.modelCode().equals(model.getCode())
                        && contract.events().contains(operation.code())).toList();
        if (contracts.isEmpty()) return;
        contracts.forEach(contract -> publications.requireSourceBinding(tenant, contract, model));
        // Only schema-owned identifiers enter SQL; identity and tenant remain bound parameters.
        String table = SqlSafetyUtils.requireIdentifier(model.getTableName(), "published event table");
        var columns = new LinkedHashMap<String, String>();
        columns.put("pid", "pid");
        columns.put("row_version", "row_version");
        for (var contract : contracts) {
            for (var field : contract.fields().values()) {
                if (!field.readable() || columns.containsKey(field.source())) continue;
                var definition = model.getFields().stream().filter(f -> f.getCode().equals(field.source()))
                        .findFirst().orElseThrow(() -> new IllegalStateException("Published event field no longer exists"));
                if (definition.isJsonbVirtual())
                    throw new IllegalStateException("Published event requires a physical source field");
                columns.put(field.source(), SqlSafetyUtils.requireIdentifier(
                        definition.getColumnName() == null ? definition.getCode() : definition.getColumnName(), "published event field"));
            }
        }
        String selection = columns.entrySet().stream().map(entry -> entry.getValue() + " AS "
                + "\"" + SqlSafetyUtils.requireIdentifier(entry.getKey(), "event source alias") + "\"")
                .collect(java.util.stream.Collectors.joining(", "));
        // Lock protects the before-delete snapshot from a concurrent writer until deletion commits.
        var row = jdbc.queryForMap("SELECT " + selection + " FROM " + table
                + " WHERE tenant_id = ? AND pid = ? FOR UPDATE", tenant, pid);
        long sequence = ((Number) row.get("row_version")).longValue()
                + (operation == Operation.DELETED ? 1 : 0);
        for (var contract : contracts) {
            var source = new LinkedHashMap<String, Object>();
            for (var field : contract.fields().values()) {
                if (field.readable()) source.put(field.source(), normalize(row.get(field.source()), field.type()));
            }
            String eventId = "evt_" + UniqueIdGenerator.generate();
            String requestId = MDC.get("requestId");
            integration.enqueue(new IntegrationEventEnvelope(IntegrationEventEnvelope.VERSION_1, eventId,
                    "open.resource." + contract.code() + "." + operation.code(), "dynamic-model",
                    pid, Instant.now(), tenant, requestId == null || requestId.isBlank() ? eventId : requestId,
                    null, "open.resource:" + contract.code() + ":" + pid, sequence,
                    Map.of("resource", contract.code(), "operation", operation.code(),
                            "schemaVersion", contract.schemaVersion(), "recordVersion", sequence,
                            "contract", json.convertValue(contract, new TypeReference<Map<String, Object>>() { }),
                            "source", source), Map.of()));
        }
    }

    private Object normalize(Object value, OpenApiResourceContract.Type type) {
        if (value == null) return null;
        if (type == OpenApiResourceContract.Type.OBJECT || type == OpenApiResourceContract.Type.ARRAY) {
            if (value instanceof Map<?, ?> || value instanceof List<?>) return value;
            try { return json.readValue(value.toString(), Object.class); }
            catch (JsonProcessingException error) { throw new IllegalStateException("Published event JSON is invalid", error); }
        }
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant().toString();
        if (value instanceof java.sql.Date date) return date.toLocalDate().toString();
        if (value instanceof java.time.temporal.TemporalAccessor) return value.toString();
        return value;
    }
}
