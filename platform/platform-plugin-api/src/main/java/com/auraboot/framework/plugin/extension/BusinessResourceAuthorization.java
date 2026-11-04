package com.auraboot.framework.plugin.extension;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server-owned resource contract for one already-authorized business command.
 * This is executable owner policy, not another set of user-grantable permissions.
 * Returning null denies the exact operation. Unknown resources/actions must deny.
 */
public interface BusinessResourceAuthorization {
    /** A fresh policy may retain locked source facts for this invocation only. */
    default BusinessResourceAuthorization newInvocationAuthorization() { return this; }

    /** Optional installed dependencies may yield null only on an approved nullable read. */
    default Set<String> optionalBusinessResourceModels() { return Set.of(); }

    /** Immutable owner lower bound; mutable command metadata cannot substitute a weaker duty. */
    Boundary businessBoundary();

    record Boundary(String commandCode,String rootModel,String targetAction,Set<String> permissions) {
        public Boundary {
            permissions=Set.copyOf(permissions);
            identifier(rootModel);
            if(commandCode==null || commandCode.isBlank() || !commandCode.equals(commandCode.strip())
                    || commandCode.length()>128 || !Set.of("read","update","delete").contains(targetAction))
                throw new IllegalArgumentException("Exact business boundary required");
        }
    }

    Grant authorizeResource(CommandHandlerExtension.TargetAuthorizationContext root, Request request);

    /** Finite dependency inventory; undeclared models are rejected before any lookup. */
    Set<String> businessResourceModels();

    /** Only these top-level business-result fields may leave the handler. */
    Set<String> businessResultFields();

    enum Action { READ, QUERY, CREATE, UPDATE, DELETE }

    /** Current records are host-loaded. Filters and changes are intentions, never authority. */
    record Request(Action action, String modelCode, String recordId, Map<String, Object> record,
                   Map<String, Object> changes, Map<String, Object> filters) {
        public Request {
            java.util.Objects.requireNonNull(action, "action");
            identifier(modelCode);
            record = snapshot(record);
            changes = snapshot(changes);
            filters = snapshot(filters);
        }
    }

    /** Projection and mutation fields for this exact resource operation, not an unrestricted model. */
    record Grant(Set<String> readableFields, Set<String> writableFields, int maximumRows) {
        public Grant {
            readableFields = Set.copyOf(readableFields);
            writableFields = Set.copyOf(writableFields);
            if (!readableFields.containsAll(Set.of("pid", "tenant_id", "row_version"))
                    || readableFields.size() > 256 || writableFields.size() > 256
                    || maximumRows < 1 || maximumRows > 1_000) {
                throw new IllegalArgumentException("Bounded business resource fields and row limit required");
            }
            readableFields.forEach(BusinessResourceAuthorization::identifier);
            writableFields.forEach(BusinessResourceAuthorization::identifier);
        }
    }

    static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
            throw new IllegalArgumentException("Exact business resource/field code required");
        }
    }

    /** Defensive immutable JSON-like facts; callbacks cannot rewrite the host's stored row. */
    static Map<String, Object> snapshot(Map<String, Object> input) {
        if (input == null) return Map.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> copy = (Map<String, Object>) freeze(input, 0);
        return copy;
    }

    private static Object freeze(Object value, int depth) {
        if (depth > 16) throw new IllegalArgumentException("Business resource facts exceed depth budget");
        if (value instanceof Map<?, ?> map) {
            if (map.size() > 1_024) throw new IllegalArgumentException("Business resource facts exceed field budget");
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (!(key instanceof String field)) throw new IllegalArgumentException("String fact keys required");
                copy.put(field, freeze(item, depth + 1));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            if (list.size() > 10_000) throw new IllegalArgumentException("Business resource facts exceed list budget");
            return list.stream().map(item -> freeze(item, depth + 1)).toList();
        }
        if (value instanceof java.sql.Date date) return date.toLocalDate().toString();
        if (value instanceof java.sql.Time time) return time.toLocalTime().toString();
        if (value instanceof java.util.Date date) return date.toInstant().toString();
        if (value instanceof java.time.temporal.TemporalAccessor || value instanceof java.util.UUID) return value.toString();
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
                || value instanceof Double || value instanceof Float || value instanceof java.math.BigDecimal
                || value instanceof java.math.BigInteger) return value;
        throw new IllegalArgumentException("Unsupported business resource fact type");
    }
}
