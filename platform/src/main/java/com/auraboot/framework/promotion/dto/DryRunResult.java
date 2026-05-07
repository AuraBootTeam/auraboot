package com.auraboot.framework.promotion.dto;

import com.auraboot.framework.promotion.diff.SemanticDiffEntry;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Outcome of a promotion dry-run. PoC scope: detects content conflicts on PAGE_SCHEMA resources.
 * Missing-dependency analysis (e.g. referenced model not in target) is task #8.
 */
@Data
public class DryRunResult {

    /** When this result was produced. Caller checks freshness (24h cap) before allowing apply. */
    private Date validatedAt;

    /** True iff conflicts.isEmpty() && missingDependencies.isEmpty(). */
    private boolean valid;

    private List<Conflict> conflicts = new ArrayList<>();

    /** Reserved for task #8 reverse-reference impact analysis. */
    private List<MissingDependency> missingDependencies = new ArrayList<>();

    /**
     * A unit's source content differs from what the target env currently holds for the same
     * resource. Apply would overwrite target — caller must explicitly accept.
     */
    @Data
    public static class Conflict {
        private String resourceType;
        private String resourcePid;
        private Integer sourceVersion;
        private Integer targetVersion;
        /** Short human-readable summary, e.g. "blocks differ" or "title changed". */
        private String reason;
        /** Field-level diff entries; powers the Diff Viewer side-by-side highlight. */
        private List<SemanticDiffEntry> diff = new ArrayList<>();
    }

    @Data
    public static class MissingDependency {
        private String resourceType;
        private String resourcePid;
        /** What references it (e.g. "page tcrm_lead_list references model tcrm_lead"). */
        private String referencedBy;
    }
}
