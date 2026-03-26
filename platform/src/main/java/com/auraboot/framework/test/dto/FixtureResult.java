package com.auraboot.framework.test.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class FixtureResult {
    private boolean success;
    private String fixtureName;
    private String testRunId;
    private int recordsCreated;
    private List<String> recordIds;
    private Map<String, Object> metadata;
}
