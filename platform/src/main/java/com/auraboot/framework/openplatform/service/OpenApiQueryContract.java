package com.auraboot.framework.openplatform.service;

import java.util.*;
import static com.auraboot.framework.openplatform.service.OpenApiResourceContract.*;

/** A public query pins its approved NamedQuery semantics and explicitly names every source grant. */
public record OpenApiQueryContract(String code, String queryCode, int schemaVersion, String sourceFingerprint,
                                   Map<String, String> scopes, Map<String, String> sourceResources,
                                   Map<String, Parameter> parameters, Map<String, Field> fields,
                                   int maxPageSize, int maxRows, int timeoutMs, int maxConcurrent) {
    private static final Set<String> RESERVED = Set.of("tenantId", "currentUserId", "currentUserPid");
    public record Parameter(Type type, boolean required, boolean nullable, Field items) {
        public Parameter {
            require(type != null && type != Type.OBJECT, "Scalar or bounded array parameter required");
            require((type == Type.ARRAY) == (items != null), "Array parameter item schema required");
            if (items != null) require(items.type() != Type.OBJECT && items.type() != Type.ARRAY
                    && items.referenceResource() == null, "Scalar query array items required");
        }
        Object validate(String name, Object value) {
            return validateFieldValue(new Field(name, type, false, true, required, nullable, Set.of(), false, null, Map.of(), items), value);
        }
    }
    public OpenApiQueryContract {
        // The shared 100-character publication key reserves six characters for its query namespace.
        require(code != null && code.matches("[a-z][a-z0-9.-]{1,93}"), "Invalid public query code");
        require(queryCode != null && queryCode.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}"), "Invalid source query code");
        require(schemaVersion > 0 && sourceFingerprint != null && sourceFingerprint.matches("[a-f0-9]{64}"), "Pinned query fingerprint required");
        scopes = Map.copyOf(scopes == null ? Map.of() : scopes);
        require(scopes.size() == 1 && scopes.containsKey("execute") && scopes.get("execute").matches("[a-z][a-z0-9.:-]{1,159}"), "Exactly one execute scope required");
        sourceResources = Map.copyOf(sourceResources == null ? Map.of() : sourceResources);
        require(!sourceResources.isEmpty() && sourceResources.size() <= 16, "Explicit bounded query source grants required");
        sourceResources.forEach((model, resource) -> require(model.matches("[A-Za-z][A-Za-z0-9_]{0,127}")
                && resource.matches("[a-z][a-z0-9.-]{1,99}"), "Invalid query source binding"));
        parameters = Map.copyOf(parameters == null ? Map.of() : parameters);
        require(parameters.size() <= 32, "Query parameter budget exceeded");
        parameters.keySet().forEach(name -> require(name.matches("[A-Za-z][A-Za-z0-9_]{0,99}") && !RESERVED.contains(name)
                && !name.startsWith("machine_") && !name.startsWith("base_") && !name.startsWith("cond_")
                && !name.startsWith("bw_") && !name.startsWith("uc_") && !name.startsWith("keyword_"), "Reserved or invalid query parameter"));
        fields = Map.copyOf(fields == null ? Map.of() : fields); validatePublicFields(fields);
        fields.values().forEach(field -> requireReadOnly(field));
        require(maxPageSize >= 1 && maxPageSize <= 100 && maxRows >= maxPageSize && maxRows <= 10000,
                "Bounded public query page and result budgets required");
        require(timeoutMs >= 1 && timeoutMs <= 30000 && maxConcurrent >= 1 && maxConcurrent <= 32, "Invalid public query execution budget");
    }
    private static void requireReadOnly(Field field) {
        require(field.readable() && !field.writable() && !field.sortable() && field.operators().isEmpty()
                && field.referenceResource() == null, "Query output fields are read-only projections");
        field.properties().values().forEach(OpenApiQueryContract::requireReadOnly);
        if (field.items() != null) requireReadOnly(field.items());
    }
    public Map<String, Object> mapParameters(Map<String, Object> input) {
        require(input != null && parameters.keySet().containsAll(input.keySet()), "Unknown public query parameter");
        Map<String, Object> result = new LinkedHashMap<>();
        parameters.forEach((name, parameter) -> {
            require(!parameter.required() || input.containsKey(name), "Required public query parameter: " + name);
            if (input.containsKey(name)) result.put(name, parameter.validate(name, input.get(name)));
        });
        return Collections.unmodifiableMap(result);
    }
    public Map<String, Object> project(Map<String, Object> row, Set<String> readable) {
        var permitted = new LinkedHashMap<String, Field>();
        readable.forEach(alias -> permitted.put(alias, fields.get(alias)));
        return projectFields(permitted, row);
    }
    public void requireCompatibleWith(OpenApiQueryContract previous) {
        require(code.equals(previous.code()) && queryCode.equals(previous.queryCode()) && sourceFingerprint.equals(previous.sourceFingerprint()),
                "Public query source semantics cannot change within a major version");
        require(scopes.equals(previous.scopes()) && sourceResources.equals(previous.sourceResources()), "Query scope/source grant bindings cannot change");
        previous.parameters().forEach((name, parameter) -> require(parameter.equals(parameters.get(name)), "Existing query parameter cannot change"));
        parameters.forEach((name, parameter) -> require(previous.parameters().containsKey(name) || !parameter.required(), "New required query parameter is incompatible"));
        previous.fields().forEach((alias, field) -> require(field.equals(fields.get(alias)), "Existing query output cannot change"));
        require(maxPageSize >= previous.maxPageSize() && maxRows >= previous.maxRows() && timeoutMs >= previous.timeoutMs()
                && maxConcurrent >= previous.maxConcurrent(), "Query execution budget cannot shrink");
    }
    public String storageCode() { return "query:" + code; }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
