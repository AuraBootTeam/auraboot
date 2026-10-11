package com.auraboot.framework.openplatform.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable external resource schema; model capabilities are checked separately at publication. */
public record OpenApiResourceContract(
        String code, String modelCode, int schemaVersion,
        Set<Operation> operations, Map<Operation, String> scopes,
        DataPolicy dataPolicy, Map<String, Field> fields,
        int maxPageSize, int maxConditions, int timeoutMs,
        Set<String> events, Map<String, String> sourceResources) {

    public OpenApiResourceContract(String code, String modelCode, int schemaVersion,
            Set<Operation> operations, Map<Operation, String> scopes, DataPolicy dataPolicy, Map<String, Field> fields,
            int maxPageSize, int maxConditions, int timeoutMs, Set<String> events) {
        this(code, modelCode, schemaVersion, operations, scopes, dataPolicy, fields,
                maxPageSize, maxConditions, timeoutMs, events, Map.of());
    }

    private static final Set<String> INTERNAL = Set.of(
            "id", "tenant_id", "tenantId", "created_by", "updated_by", "row_version",
            "_expectedVersion", "deleted_flag");

    public enum Operation {
        LIST, GET, CREATE, UPDATE, DELETE;
        @com.fasterxml.jackson.annotation.JsonValue public String code() { return name().toLowerCase(java.util.Locale.ROOT); }
        @com.fasterxml.jackson.annotation.JsonCreator public static Operation fromCode(String value) { return decodeEnum(values(), value); }
    }
    public enum DataPolicy {
        TENANT_WIDE, INSTALLATION_FILTER;
        @com.fasterxml.jackson.annotation.JsonValue public String code() { return name().toLowerCase(java.util.Locale.ROOT); }
        @com.fasterxml.jackson.annotation.JsonCreator public static DataPolicy fromCode(String value) { return decodeEnum(values(), value); }
    }
    public enum Type {
        STRING, PID, INTEGER, DECIMAL, BOOLEAN, DATE, DATETIME, OBJECT, ARRAY;
        @com.fasterxml.jackson.annotation.JsonValue public String code() { return name().toLowerCase(java.util.Locale.ROOT); }
        @com.fasterxml.jackson.annotation.JsonCreator public static Type fromCode(String value) { return decodeEnum(values(), value); }
    }

    private static <T extends Enum<T>> T decodeEnum(T[] values, String code) {
        for (T value : values) {
            if (value.name().toLowerCase(java.util.Locale.ROOT).equals(code)) return value;
        }
        throw new IllegalArgumentException("Unknown public contract value: " + code);
    }

    public OpenApiResourceContract {
        identifier(code, "resource code", "[a-z][a-z0-9.-]{1,99}");
        identifier(modelCode, "model code", "[A-Za-z][A-Za-z0-9_]{0,127}");
        require(schemaVersion > 0, "Positive schema version required");
        operations = Set.copyOf(operations == null ? Set.of() : operations);
        scopes = Map.copyOf(scopes == null ? Map.of() : scopes);
        fields = Map.copyOf(fields == null ? Map.of() : fields);
        events = Set.copyOf(events == null ? Set.of() : events);
        sourceResources = Map.copyOf(sourceResources == null ? Map.of() : sourceResources);
        require(sourceResources.size() <= 16, "Bounded virtual source bindings required");
        sourceResources.forEach((model, resource) -> {
            identifier(model, "source model", "[A-Za-z][A-Za-z0-9_]{0,127}");
            identifier(resource, "source resource", "[a-z][a-z0-9.-]{1,99}");
        });
        require(!operations.isEmpty() && dataPolicy != null, "Operations and data policy required");
        require(scopes.keySet().equals(operations), "Every operation requires exactly one scope declaration");
        scopes.values().forEach(scope -> identifier(scope, "scope", "[a-z][a-z0-9.:-]{1,159}"));
        require(maxPageSize >= 1 && maxPageSize <= 100, "Page size must be between 1 and 100");
        require(maxConditions >= 1 && maxConditions <= 32, "Condition budget must be between 1 and 32");
        require(timeoutMs >= 1 && timeoutMs <= 30000, "Query timeout must be between 1 and 30000 ms");
        require(fields.containsKey("pid") && fields.get("pid").readable()
                && !fields.get("pid").writable() && "pid".equals(fields.get("pid").source())
                && fields.get("pid").type() == Type.PID, "Read-only public PID required");
        fields.forEach((alias, field) -> {
            identifier(alias, "field alias", "[a-zA-Z][a-zA-Z0-9]{0,99}");
            require(!INTERNAL.contains(alias) && !INTERNAL.contains(field.source()), "Internal field is not publishable");
        });
        require(fields.values().stream().map(Field::source).distinct().count() == fields.size(),
                "A source field cannot have multiple public aliases");
        require(events.stream().allMatch(Set.of("created", "updated", "deleted")::contains),
                "Unknown resource event operation");
    }

    /** Public type and property whitelist also apply to structured values. */
    public record Field(String source, Type type, boolean readable, boolean writable,
                        boolean required, boolean nullable, Set<String> operators,
                        boolean sortable, String referenceResource, Map<String, Field> properties,
                        Field items) {
        public Field {
            identifier(source, "source field", "[A-Za-z][A-Za-z0-9_]{0,127}");
            require(type != null && (readable || writable), "Field type and access required");
            operators = Set.copyOf(operators == null ? Set.of() : operators);
            properties = Map.copyOf(properties == null ? Map.of() : properties);
            require(Set.of("EQ", "NE", "GT", "GE", "LT", "LE", "IN", "LIKE", "IS_NULL")
                    .containsAll(operators), "Unknown filter operator");
            require((operators.isEmpty() && !sortable) || readable, "Query fields must be readable");
            require((operators.isEmpty() && !sortable) || (type != Type.OBJECT && type != Type.ARRAY),
                    "Structured fields cannot declare scalar filters or sorting");
            require(type == Type.OBJECT || properties.isEmpty(), "Only object fields declare properties");
            require(type != Type.OBJECT || !properties.isEmpty(), "Object property whitelist required");
            require((type == Type.ARRAY) == (items != null), "Array item schema required only for arrays");
            require(referenceResource == null || type == Type.PID, "References must use PID");
            if (referenceResource != null) identifier(referenceResource, "reference resource", "[a-z][a-z0-9.-]{1,99}");
            properties.forEach((alias, nested) -> {
                identifier(alias, "property alias", "[a-zA-Z][a-zA-Z0-9]{0,99}");
                require(!INTERNAL.contains(alias) && !INTERNAL.contains(nested.source()), "Internal property is not publishable");
            });
        }
    }

    public Map<String, Object> mapWrite(Map<String, Object> input, boolean create) {
        require(input != null && !input.isEmpty(), "Non-empty resource payload required");
        return mapObject(fields, input, create, 0);
    }

    public Map<String, Object> project(Map<String, Object> record) {
        require(record != null, "Resource record required");
        return projectObject(fields, record, 0);
    }

    public static Map<String, Object> projectFields(Map<String, Field> fields, Map<String, Object> record) {
        return projectObject(fields, record, 0);
    }

    public static Object validateFieldValue(Field field, Object value) {
        return validateValue(field, value, true, 1);
    }

    public static void validatePublicFields(Map<String, Field> fields) {
        require(fields != null && !fields.isEmpty() && fields.size() <= 100, "Bounded public field schema required");
        fields.forEach((alias, field) -> {
            identifier(alias, "field alias", "[a-zA-Z][a-zA-Z0-9]{0,99}");
            require(field != null && !INTERNAL.contains(alias) && !INTERNAL.contains(field.source()), "Internal field is not publishable");
        });
        require(fields.values().stream().map(Field::source).distinct().count() == fields.size(), "Duplicate public source field");
    }

    public Object validateFilterValue(String alias, Object value) {
        Field field = fields.get(alias);
        require(field != null && field.readable() && field.type() != Type.OBJECT && field.type() != Type.ARRAY,
                "Scalar public query field required");
        return validateValue(field, value, false, 1);
    }

    private static Map<String, Object> mapObject(Map<String, Field> schema, Map<?, ?> input,
                                                 boolean create, int depth) {
        require(depth <= 8 && input.size() <= schema.size(), "Object exceeds schema budget");
        Map<String, Object> mapped = new LinkedHashMap<>();
        input.forEach((rawAlias, value) -> {
            require(rawAlias instanceof String, "String field name required");
            Field field = schema.get(rawAlias);
            require(field != null && field.writable(), "Unknown or read-only public field: " + rawAlias);
            mapped.put(field.source(), validateValue(field, value, true, depth + 1));
        });
        if (create) schema.forEach((alias, field) -> {
            if (field.writable() && field.required()) require(input.containsKey(alias), "Required public field: " + alias);
        });
        return Collections.unmodifiableMap(mapped);
    }

    private static Map<String, Object> projectObject(Map<String, Field> schema, Map<?, ?> record, int depth) {
        require(depth <= 8, "Object exceeds schema depth");
        Map<String, Object> result = new LinkedHashMap<>();
        schema.forEach((alias, field) -> {
            if (field.readable() && record.containsKey(field.source())) {
                result.put(alias, validateValue(field, record.get(field.source()), false, depth + 1));
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private static Object validateValue(Field field, Object value, boolean write, int depth) {
        require(depth <= 8, "Value exceeds schema depth");
        if (value == null) {
            require(field.nullable(), "Public field does not accept null");
            return null;
        }
        switch (field.type()) {
            case STRING -> require(value instanceof String && ((String) value).length() <= 65536,
                    "String value required");
            case PID -> require(value instanceof String && ((String) value).matches("[A-Za-z0-9_-]{1,64}")
                    && !((String) value).matches("[0-9]+"), "String public PID required");
            case INTEGER -> {
                require(value instanceof Number, "Integer value required");
                try { new BigDecimal(value.toString()).toBigIntegerExact(); }
                // CATCH: parsing is explicit type validation, not business recovery.
                catch (NumberFormatException | ArithmeticException exception) { throw new IllegalArgumentException("Integer value required", exception); }
            }
            case DECIMAL -> {
                require(value instanceof Number || value instanceof String, "Decimal value required");
                try { new BigDecimal(value.toString()); }
                // CATCH: reject invalid finite decimal input while preserving the cause.
                catch (NumberFormatException exception) { throw new IllegalArgumentException("Finite decimal value required", exception); }
            }
            case BOOLEAN -> require(value instanceof Boolean, "Boolean value required");
            case DATE -> {
                try { return (value instanceof java.sql.Date date ? date.toLocalDate() : LocalDate.parse(value.toString())).toString(); }
                catch (java.time.DateTimeException invalid) { throw new IllegalArgumentException("ISO date value required", invalid); }
            }
            case DATETIME -> {
                try { return (value instanceof java.sql.Timestamp timestamp ? timestamp.toInstant() : Instant.parse(value.toString())).toString(); }
                catch (java.time.DateTimeException invalid) { throw new IllegalArgumentException("ISO instant value required", invalid); }
            }
            case OBJECT -> {
                require(value instanceof Map<?, ?>, "Object value required");
                return write ? mapObject(field.properties(), (Map<?, ?>) value, true, depth)
                        : projectObject(field.properties(), (Map<?, ?>) value, depth);
            }
            case ARRAY -> {
                require(value instanceof List<?> && ((List<?>) value).size() <= 1000, "Bounded array required");
                return ((List<?>) value).stream().map(item -> validateValue(field.items(), item, write, depth + 1)).toList();
            }
        }
        return value;
    }

    /** A same-major revision must keep every existing operation and published field contract. */
    public void requireCompatibleWith(OpenApiResourceContract previous) {
        require(code.equals(previous.code()) && modelCode.equals(previous.modelCode()), "Public source binding cannot change");
        require(sourceResources.equals(previous.sourceResources()), "Public source grants cannot change");
        require(operations.containsAll(previous.operations()) && dataPolicy == previous.dataPolicy(), "Public operation/policy cannot be removed or changed");
        previous.scopes().forEach((operation, scope) -> require(scope.equals(scopes.get(operation)), "Public scope cannot change"));
        previous.fields().forEach((alias, field) -> require(field.equals(fields.get(alias)), "Existing public field contract cannot change: " + alias));
        fields.forEach((alias, field) -> require(previous.fields().containsKey(alias) || !field.required(), "New required public field is incompatible"));
        require(maxPageSize >= previous.maxPageSize() && maxConditions >= previous.maxConditions()
                && timeoutMs >= previous.timeoutMs() && events.containsAll(previous.events()), "Public budget/event contract cannot shrink");
        require(schemaVersion >= previous.schemaVersion(), "Schema version cannot decrease");
    }

    private static void identifier(String value, String label, String pattern) {
        require(value != null && value.matches(pattern), "Invalid " + label);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
