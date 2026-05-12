package com.auraboot.framework.devpipeline.importer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

@Component
public class FilePipelineMirrorWriter implements PipelineMirrorWriter {

    private final ObjectMapper objectMapper;

    public FilePipelineMirrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Path writeReadOnlyMirror(Path runDir, String runPid, Instant importedAt) {
        Path mirrorPath = runDir.resolve(".mirror.json");
        Map<String, Object> mirror = Map.of(
                "sourceOfTruth", "plugin",
                "runPid", runPid,
                "importedAt", importedAt.toString(),
                "writePolicy", "read_only_mirror"
        );
        try {
            String mirrorJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(mirror);
            Files.writeString(mirrorPath, mirrorJson + "\n");
            return mirrorPath;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write pipeline mirror: " + mirrorPath, e);
        }
    }
}
