package com.auraboot.framework.devpipeline.importer;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record PipelineImportResult(
        String runId,
        String runPid,
        PipelineImportStatus status,
        Map<String, Integer> recordCounts,
        List<String> warnings,
        Path mirrorPath,
        Path importReportPath
) {
}
