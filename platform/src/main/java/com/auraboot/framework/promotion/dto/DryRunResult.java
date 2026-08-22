package com.auraboot.framework.promotion.dto;

import com.auraboot.framework.promotion.diff.SemanticDiffEntry;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Outcome of a promotion dry-run for PAGE_SCHEMA resources. It reports ordinary content
 * conflicts separately from governed target-local release drift.
 */
@Data
public class DryRunResult {

    /** When this result was produced. Caller checks freshness (24h cap) before allowing apply. */
    private Date validatedAt;

    /** True iff conflicts/dependencies are clear and every reported drift is apply-ready. */
    private boolean valid;

    private List<Conflict> conflicts = new ArrayList<>();

    /** Reserved for task #8 reverse-reference impact analysis. */
    private List<MissingDependency> missingDependencies = new ArrayList<>();

    /** Active target releases that require an explicit production-drift fate. */
    private List<Drift> drifts = new ArrayList<>();

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
        /** What references it (e.g. "page crm_lead_common_list references model crm_lead_common"). */
        private String referencedBy;
    }

    @Data
    public static class Drift {
        private String unitPid;
        private String resourceType;
        private String resourcePid;
        private String targetResourcePid;
        private String pageKey;
        private String kind;
        private String status;
        private String fingerprint;
        private String decision;
        private String executionStatus;
        private String executionPid;
        private boolean applyReady;
        private String nextAction;
        private String activeReleasePid;
        private Long channelVersion;
        private String overridePid;
        private Integer sourceVersion;
        private Integer targetVersion;
        private List<String> options = new ArrayList<>();
    }
}
