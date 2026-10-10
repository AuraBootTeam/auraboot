package com.auraboot.framework.openplatform.service;

import java.util.*;
import static com.auraboot.framework.openplatform.service.OpenApiResourceContract.*;

/** Public artifacts derived from the published contract. Internal source mappings stay private. */
public final class OpenApiContractArtifacts {
    private OpenApiContractArtifacts() { }

    public static Map<String, Object> resource(OpenApiResourceContract contract) {
        Map<String, Object> schemas = new LinkedHashMap<>();
        schemas.put("Resource", object(contract.fields(), false, false, true));
        if (contract.operations().contains(Operation.CREATE)) schemas.put("CreateInput", object(contract.fields(), true, true, true));
        if (contract.operations().contains(Operation.UPDATE)) schemas.put("UpdateInput", object(contract.fields(), true, false, true));
        schemas.put("Page", page(ref("Resource"), false));
        var paths = new TreeMap<String, Object>();
        String collection = "/api/open/v1/resources/" + contract.code();
        for (Operation operation : contract.operations()) {
            boolean record = Set.of(Operation.GET, Operation.UPDATE, Operation.DELETE).contains(operation);
            String path = collection + (record ? "/{recordPid}" : "");
            @SuppressWarnings("unchecked")
            var methods = (Map<String, Object>) paths.computeIfAbsent(path, ignored -> new TreeMap<String, Object>());
            String method = switch (operation) { case LIST, GET -> "get"; case CREATE -> "post"; case UPDATE -> "patch"; case DELETE -> "delete"; };
            var endpoint = endpoint(contract.code() + "." + operation.code(), contract.scopes().get(operation));
            var parameters = new ArrayList<Map<String, Object>>();
            if (record) parameters.add(parameter("recordPid", "path", true, pid()));
            if (operation == Operation.LIST) {
                parameters.add(parameter("limit", "query", false, Map.of("type", "integer", "minimum", 1, "maximum", contract.maxPageSize())));
                parameters.add(parameter("cursor", "query", false, Map.of("type", "string")));
                var sortable = new TreeSet<String>(); sortable.add("pid");
                contract.fields().forEach((alias, field) -> { if (field.sortable()) sortable.add(alias); });
                parameters.add(parameter("sort", "query", false, Map.of("type", "string", "enum", sortable, "default", "pid")));
                parameters.add(parameter("direction", "query", false, Map.of("type", "string", "enum", List.of("ASC", "DESC"), "default", "ASC")));
                var filters = new ArrayList<Map<String, Object>>();
                new TreeMap<>(contract.fields()).forEach((alias, field) -> field.operators().stream().sorted().forEach(operator -> {
                    var properties = new LinkedHashMap<String, Object>();
                    properties.put("field", Map.of("const", alias)); properties.put("operator", Map.of("const", operator));
                    if (!"IS_NULL".equals(operator)) properties.put("value", "IN".equals(operator)
                            ? Map.of("type", "array", "minItems", 1, "maxItems", 100, "items", nonNullable(field, false)) : nonNullable(field, false));
                    filters.add(Map.of("type", "object", "additionalProperties", false, "properties", properties,
                            "required", "IS_NULL".equals(operator) ? List.of("field", "operator") : List.of("field", "operator", "value")));
                }));
                if (!filters.isEmpty()) parameters.add(Map.of("name", "filters", "in", "query", "required", false,
                        "content", content(Map.of("type", "array", "maxItems", contract.maxConditions(), "items", Map.of("oneOf", filters)))));
            }
            if (Set.of(Operation.CREATE, Operation.UPDATE, Operation.DELETE).contains(operation)) {
                parameters.add(parameter("Idempotency-Key", "header", true, Map.of("type", "string", "pattern", "^[A-Za-z0-9._:-]{8,160}$")));
                if (operation != Operation.CREATE) parameters.add(parameter("If-Match", "header", true, Map.of("type", "string", "description", "Strong ETag returned by a versioned resource read")));
            }
            endpoint.put("parameters", parameters);
            if (operation == Operation.CREATE || operation == Operation.UPDATE) endpoint.put("requestBody", Map.of("required", true,
                    "content", content(ref(operation == Operation.CREATE ? "CreateInput" : "UpdateInput"))));
            var responses = errors();
            var headers = new LinkedHashMap<String, Object>();
            headers.put("X-Request-Id", Map.of("schema", Map.of("type", "string")));
            if (operation != Operation.LIST && operation != Operation.DELETE) headers.put("ETag", Map.of("schema", Map.of("type", "string")));
            if (operation == Operation.CREATE) headers.put("Location", Map.of("schema", Map.of("type", "string")));
            if (Set.of(Operation.CREATE, Operation.UPDATE, Operation.DELETE).contains(operation)) headers.put("Idempotent-Replay", Map.of("schema", Map.of("type", "string", "enum", List.of("true"))));
            var success = new LinkedHashMap<String, Object>(); success.put("description", "Successful public " + operation.code()); success.put("headers", headers);
            if (operation != Operation.DELETE) success.put("content", content(ref(operation == Operation.LIST ? "Page" : "Resource")));
            responses.put(operation == Operation.CREATE ? "201" : operation == Operation.DELETE ? "204" : "200", success);
            endpoint.put("responses", responses); endpoint.put("x-timeout-ms", contract.timeoutMs()); methods.put(method, endpoint);
        }
        var result = document(contract.code(), contract.schemaVersion(), contract.scopes().values(), paths, schemas);
        if (!contract.events().isEmpty()) {
            schemas.put("ModelEvent", modelEvent(contract));
            var webhooks = new TreeMap<String, Object>();
            for (String operation : contract.events()) webhooks.put("open.resource." + contract.code() + "." + operation,
                    Map.of("post", Map.of("security", List.of(), "description", "When a subscription secret is configured, verify the HMAC over the raw HTTP bytes before parsing; deduplicate event ID and compare recordVersion",
                            "parameters", List.of(parameter("X-Webhook-Event", "header", true, Map.of("type", "string")),
                                    parameter("X-Webhook-Event-Version", "header", true, Map.of("type", "string")),
                                    parameter("X-Webhook-Timestamp", "header", true, Map.of("type", "string")),
                                    parameter("X-Webhook-Delivery", "header", true, Map.of("type", "string")),
                                    parameter("X-Webhook-Signature", "header", false, Map.of("type", "string", "description", "Present when a subscription secret is configured; HMAC over the timestamp and raw payload bytes"))),
                            "requestBody", Map.of("required", true, "content", content(ref("ModelEvent"))),
                            "responses", Map.of("200", Map.of("description", "Notification accepted")))));
            result.put("webhooks", webhooks);
        }
        return result;
    }

    public static Map<String, Object> query(OpenApiQueryContract contract) {
        var properties = new TreeMap<String, Object>(); var required = new ArrayList<String>();
        new TreeMap<>(contract.parameters()).forEach((name, parameter) -> {
            properties.put(name, field(new Field(name, parameter.type(), false, true, parameter.required(), parameter.nullable(), Set.of(), false, null, Map.of(), parameter.items()), true));
            if (parameter.required()) required.add(name);
        });
        var parameters = new LinkedHashMap<String, Object>(); parameters.put("type", "object"); parameters.put("additionalProperties", false); parameters.put("properties", properties);
        if (!required.isEmpty()) parameters.put("required", required);
        var request = new LinkedHashMap<String, Object>(); request.put("type", "object"); request.put("additionalProperties", false);
        request.put("properties", Map.of("parameters", parameters, "limit", Map.of("type", "integer", "minimum", 1, "maximum", contract.maxPageSize()), "cursor", Map.of("type", "string")));
        if (!required.isEmpty()) request.put("required", List.of("parameters"));
        var schemas = new LinkedHashMap<String, Object>(); schemas.put("QueryParameters", parameters); schemas.put("QueryRequest", request);
        schemas.put("QueryItem", object(contract.fields(), false, false)); schemas.put("QueryPage", page(ref("QueryItem"), true));
        var endpoint = endpoint(contract.code() + ".execute", contract.scopes().get("execute"));
        endpoint.put("requestBody", Map.of("required", true, "content", content(ref("QueryRequest"))));
        var responses = errors(); responses.put("200", Map.of("description", "Installation-projected published query result", "content", content(ref("QueryPage")))); endpoint.put("responses", responses);
        endpoint.put("x-max-rows", contract.maxRows()); endpoint.put("x-timeout-ms", contract.timeoutMs()); endpoint.put("x-max-concurrent", contract.maxConcurrent());
        return document(contract.code(), contract.schemaVersion(), contract.scopes().values(), Map.of("/api/open/v1/queries/" + contract.code() + ":execute", Map.of("post", endpoint)), schemas);
    }

    private static Map<String, Object> document(String code, int version, Collection<String> scopes, Map<String, Object> paths, Map<String, Object> schemas) {
        var scopeDescriptions = new TreeMap<String, String>(); scopes.forEach(scope -> scopeDescriptions.put(scope, "Explicit installation capability"));
        var result = new LinkedHashMap<String, Object>(); result.put("openapi", "3.1.0");
        result.put("info", Map.of("title", "AuraBoot " + code, "version", "1." + version, "description", "Published schema; installation grants may narrow operations and visible fields"));
        result.put("servers", List.of(Map.of("url", "/"))); result.put("paths", paths); result.put("x-schema-version", version);
        result.put("components", Map.of("schemas", schemas, "securitySchemes", Map.of("oauth2ClientCredentials",
                Map.of("type", "oauth2", "flows", Map.of("clientCredentials", Map.of("tokenUrl", "/oauth2/token", "scopes", scopeDescriptions))))));
        return result;
    }
    private static Map<String, Object> endpoint(String id, String scope) {
        var result = new LinkedHashMap<String, Object>(); result.put("operationId", id); result.put("security", List.of(Map.of("oauth2ClientCredentials", List.of(scope)))); return result;
    }
    private static Map<String, Object> errors() {
        var result = new LinkedHashMap<String, Object>();
        for (String code : List.of("400", "401", "403", "404", "409", "412", "429", "503", "504")) result.put(code,
                Map.of("description", "Stable public error; no automatic write retry", "content", content(Map.of("type", "object", "properties", Map.of("code", Map.of("type", "string"), "message", Map.of("type", "string"))))));
        return result;
    }
    private static Map<String, Object> ref(String name) { return Map.of("$ref", "#/components/schemas/" + name); }
    private static Map<String, Object> content(Map<String, Object> schema) { return Map.of("application/json", Map.of("schema", schema)); }
    private static Map<String, Object> parameter(String name, String location, boolean required, Map<String, Object> schema) { return Map.of("name", name, "in", location, "required", required, "schema", schema); }
    private static Map<String, Object> pid() { return Map.of("type", "string", "pattern", "^(?![0-9]+$)[A-Za-z0-9_-]{1,64}$"); }
    private static Map<String, Object> page(Map<String, Object> item, boolean query) {
        var properties = new LinkedHashMap<String, Object>(); properties.put("items", Map.of("type", "array", "items", item)); properties.put("hasMore", Map.of("type", "boolean")); properties.put("nextCursor", Map.of("type", List.of("string", "null")));
        if (query) { properties.put("total", Map.of("type", "integer", "minimum", 0)); properties.put("schemaVersion", Map.of("type", "integer", "minimum", 1)); }
        return Map.of("type", "object", "additionalProperties", false, "properties", properties, "required", List.copyOf(properties.keySet()));
    }
    private static Map<String, Object> modelEvent(OpenApiResourceContract contract) {
        var properties = new LinkedHashMap<String, Object>(); properties.put("id", Map.of("type", "string"));
        properties.put("type", Map.of("type", "string", "enum", contract.events().stream().sorted().map(op -> "open.resource." + contract.code() + "." + op).toList()));
        properties.put("schemaVersion", Map.of("type", "integer", "const", contract.schemaVersion())); properties.put("occurredAt", Map.of("type", "string", "format", "date-time"));
        properties.put("subject", Map.of("type", "object", "additionalProperties", false, "properties", Map.of("type", Map.of("const", contract.code()), "pid", pid()), "required", List.of("type", "pid")));
        properties.put("recordVersion", Map.of("type", "integer", "minimum", 1)); properties.put("correlationId", Map.of("type", List.of("string", "null"))); properties.put("data", ref("Resource"));
        return Map.of("type", "object", "additionalProperties", false, "properties", properties, "required", List.copyOf(properties.keySet()));
    }
    private static Map<String, Object> object(Map<String, Field> fields, boolean write, boolean create) {
        return object(fields, write, create, false);
    }
    private static Map<String, Object> object(Map<String, Field> fields, boolean write, boolean create, boolean root) {
        var properties = new TreeMap<String, Object>(); var required = new ArrayList<String>();
        new TreeMap<>(fields).forEach((alias, field) -> {
            if (write ? field.writable() : field.readable()) {
                properties.put(alias, field(field, write));
                if ((write && create && field.required()) || (!write && root && "pid".equals(alias))) required.add(alias);
            }
        });
        var result = new LinkedHashMap<String, Object>(); result.put("type", "object"); result.put("additionalProperties", false); result.put("properties", properties);
        if (write && root) result.put("minProperties", 1);
        if (!required.isEmpty()) result.put("required", required);
        return result;
    }
    private static Map<String, Object> field(Field field, boolean write) {
        var schema = nonNullable(field, write);
        return field.nullable() ? Map.of("anyOf", List.of(schema, Map.of("type", "null"))) : schema;
    }
    private static Map<String, Object> nonNullable(Field field, boolean write) {
        return switch (field.type()) {
            case STRING -> Map.of("type", "string", "maxLength", 65536);
            case PID -> pid();
            case INTEGER -> Map.of("type", "integer");
            case DECIMAL -> Map.of("type", List.of("number", "string"), "pattern", "^[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?$");
            case BOOLEAN -> Map.of("type", "boolean");
            case DATE -> Map.of("type", "string", "format", "date");
            case DATETIME -> Map.of("type", "string", "format", "date-time");
            case OBJECT -> object(field.properties(), write, true);
            case ARRAY -> Map.of("type", "array", "maxItems", 1000, "items", field(field.items(), write));
        };
    }

    public static String resourceTypes(OpenApiResourceContract contract) {
        var result = new StringBuilder("// AuraBoot " + contract.code() + " schema " + contract.schemaVersion() + "\n");
        result.append("export type Resource = ").append(objectType(contract.fields(), false, false, true)).append(";\n");
        if (contract.operations().contains(Operation.CREATE)) result.append("export type CreateInput = ").append(objectType(contract.fields(), true, true)).append(";\n");
        if (contract.operations().contains(Operation.UPDATE)) result.append("export type UpdateInput = ").append(objectType(contract.fields(), true, false)).append(";\n");
        if (!contract.events().isEmpty()) {
            result.append("export type ModelEvent = { id: string; type: ");
            result.append(String.join(" | ", contract.events().stream().sorted().map(op -> "\"open.resource." + contract.code() + "." + op + "\"").toList()));
            result.append("; schemaVersion: ").append(contract.schemaVersion()).append("; occurredAt: string; subject: { type: \"")
                    .append(contract.code()).append("\"; pid: string }; recordVersion: number; correlationId: string | null; data: Resource };\n");
        }
        return result.toString();
    }
    public static String queryTypes(OpenApiQueryContract contract) {
        var parameters = new TreeMap<String, Field>(); contract.parameters().forEach((name, p) -> parameters.put(name, new Field(name, p.type(), false, true, p.required(), p.nullable(), Set.of(), false, null, Map.of(), p.items())));
        return "// AuraBoot " + contract.code() + " schema " + contract.schemaVersion() + "\n"
                + "export type QueryParameters = " + objectType(parameters, true, true) + ";\n"
                + "export type QueryItem = " + objectType(contract.fields(), false, false) + ";\n";
    }
    private static String objectType(Map<String, Field> fields, boolean write, boolean create) {
        return objectType(fields, write, create, false);
    }
    private static String objectType(Map<String, Field> fields, boolean write, boolean create, boolean root) {
        var result = new StringBuilder("{ "); new TreeMap<>(fields).forEach((alias, field) -> {
            if (write ? field.writable() : field.readable()) result.append('"').append(alias).append('"')
                    .append((write && create && field.required()) || (!write && root && "pid".equals(alias)) ? "" : "?")
                    .append(": ").append(type(field, write)).append("; ");
        }); return result.append('}').toString();
    }
    private static String type(Field field, boolean write) {
        String result = switch (field.type()) {
            case STRING, PID, DATE, DATETIME -> "string";
            case INTEGER -> "number";
            case DECIMAL -> "number | string";
            case BOOLEAN -> "boolean";
            case OBJECT -> objectType(field.properties(), write, true);
            case ARRAY -> "Array<" + type(field.items(), write) + ">";
        }; return result + (field.nullable() ? " | null" : "");
    }
}
