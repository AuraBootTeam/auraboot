package com.auraboot.framework.devpipeline.importer;

import java.util.Map;

public record PipelineImportPreview(
        String runId,
        Map<String, Integer> recordCounts
) {
}
