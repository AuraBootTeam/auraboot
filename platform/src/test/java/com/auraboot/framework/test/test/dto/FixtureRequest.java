package com.auraboot.framework.test.dto;

import lombok.Data;
import java.util.Map;

@Data
public class FixtureRequest {
    /** Fixture name: "records", "approval", "dashboard", "crossplatform" */
    private String name;
    /** Backward-compatible alias used by older mobile test clients. */
    private String fixture;
    /** Arbitrary parameters for the fixture */
    private Map<String, Object> params;
    /** Optional test run ID for cross-platform coordination */
    private String testRunId;

    public String getEffectiveName() {
        return name != null && !name.isBlank() ? name : fixture;
    }
}
