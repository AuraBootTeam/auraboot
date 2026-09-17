package com.auraboot.framework.permission.engine.model;

import java.util.List;

/**
 * One declared child→aggregate-root edge with the root record pids currently shared with the
 * caller.
 *
 * <p>Rows of the child resource whose {@code referenceField} equals one of {@code rootRecordPids}
 * belong to the caller's shared row surface: the explicit share on the aggregate root must not be
 * erased by the child resource's own surface data scope. Edges are explicit model declarations
 * ({@code extension.recordShare = {rootModel, referenceField}}) — reference fields alone never
 * imply aggregation, so sharing a lookup target does not leak its referrers.
 *
 * @param referenceField  field code on the child resource holding the root record pid
 * @param rootRecordPids  public pids of the root records currently shared with the caller
 */
public record SharedRootReference(String referenceField, List<String> rootRecordPids) {

    public SharedRootReference {
        rootRecordPids = rootRecordPids == null ? List.of() : List.copyOf(rootRecordPids);
    }
}
