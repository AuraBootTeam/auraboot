package com.auraboot.framework.promotion.diff;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One field-level change between two JSON document versions. Path uses dotted notation with
 * array indices, e.g. {@code blocks[0].buttons[2].label}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SemanticDiffEntry {

    public enum Op { ADD, MODIFY, DELETE }

    /** Dotted path inside the source document. */
    private String path;

    private Op op;

    /** Source-side value (null for ADD). */
    private Object oldValue;

    /** Target-side value (null for DELETE). */
    private Object newValue;
}
