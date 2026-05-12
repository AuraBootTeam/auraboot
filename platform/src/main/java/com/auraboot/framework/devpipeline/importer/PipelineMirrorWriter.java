package com.auraboot.framework.devpipeline.importer;

import java.nio.file.Path;
import java.time.Instant;

public interface PipelineMirrorWriter {
    Path writeReadOnlyMirror(Path runDir, String runPid, Instant importedAt);
}
