package com.auraboot.framework.devpipeline.importer;

import java.nio.file.Path;

public record PipelineImportRequest(
        Path packetPath,
        boolean dryRun,
        ConflictStrategy conflictStrategy,
        boolean finalizeMirror,
        String importedBy
) {
    public PipelineImportRequest {
        if (packetPath == null) {
            throw new IllegalArgumentException("packetPath is required");
        }
        if (conflictStrategy == null) {
            conflictStrategy = ConflictStrategy.ERROR;
        }
    }
}
