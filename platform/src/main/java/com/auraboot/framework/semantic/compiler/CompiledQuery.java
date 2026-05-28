package com.auraboot.framework.semantic.compiler;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Set;

/**
 * Output of {@link MetricCompiler#compile}.
 *
 * <p>{@link #sql} is a parameterised PreparedStatement template using
 * {@code ?} placeholders. {@link #params} is the ordered argument list.
 *
 * <p>{@link #sqlFingerprint} is the SHA-256 of the normalised SQL string
 * (whitespace collapsed, ASCII lowercase). Used by the audit log and by
 * the v0.2 result cache key. Excludes {@link #params} on purpose so two
 * structurally identical queries with different filter values share a
 * fingerprint.
 */
@Data
@AllArgsConstructor
public class CompiledQuery {
    private String sql;
    private List<Object> params;
    private Set<String> referencedColumns;
    private String sqlFingerprint;
}
