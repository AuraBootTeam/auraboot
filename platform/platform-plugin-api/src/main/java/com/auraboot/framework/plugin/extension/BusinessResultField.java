package com.auraboot.framework.plugin.extension;

/** Server-owned scalar or empty protocol result. Model data is never an acknowledgement. */
public record BusinessResultField(Kind kind, String sourceField, Object constant, int maximum) {
    public enum Kind { ROOT_FIELD, ROOT_ID, CONSTANT, BOOLEAN, COUNT, DERIVED_ROOT_FIELD }
    public BusinessResultField {
        java.util.Objects.requireNonNull(kind);
        if (kind == Kind.ROOT_FIELD || kind == Kind.DERIVED_ROOT_FIELD) BusinessResourceAuthorization.identifier(sourceField);
        else if (sourceField != null) throw new IllegalArgumentException("Unexpected result source");
        if (kind == Kind.CONSTANT) {
            // Preserve existing empty validation envelopes without accepting any model rows.
            if (constant instanceof java.util.List<?> list && list.isEmpty()) constant = java.util.List.of();
            if (!(constant == null || constant instanceof String || constant instanceof Boolean
                    || constant instanceof Integer || constant instanceof Long
                    || constant instanceof java.util.List<?> list && list.isEmpty()))
                throw new IllegalArgumentException("Immutable scalar or empty-list result constant required");
        } else if (constant != null) throw new IllegalArgumentException("Unexpected result constant");
        if (kind == Kind.COUNT ? maximum < 0 : maximum != 0)
            throw new IllegalArgumentException("Invalid result count bound");
    }
    public static BusinessResultField rootField(String field) { return new BusinessResultField(Kind.ROOT_FIELD,field,null,0); }
    /** Independent scalar derived from an unmasked readable root field; never a second alias of its raw value. */
    public static BusinessResultField derivedRootField(String field) { return new BusinessResultField(Kind.DERIVED_ROOT_FIELD,field,null,0); }
    public static BusinessResultField rootIdentity() { return new BusinessResultField(Kind.ROOT_ID,null,null,0); }
    public static BusinessResultField constant(Object value) { return new BusinessResultField(Kind.CONSTANT,null,value,0); }
    public static BusinessResultField flag() { return new BusinessResultField(Kind.BOOLEAN,null,null,0); }
    public static BusinessResultField count(int maximum) { return new BusinessResultField(Kind.COUNT,null,null,maximum); }
}
