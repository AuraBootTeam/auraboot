package com.auraboot.framework.plugin.source;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Plugin source backed by a local filesystem directory.
 * This is the default implementation used for CLI-based plugin imports.
 *
 * @since 7.2.0
 */
public class FileSystemPluginSource implements PluginSource {

    private final Path rootDir;

    public FileSystemPluginSource(Path rootDir) {
        if (rootDir == null) {
            throw new IllegalArgumentException("Root directory must not be null");
        }
        this.rootDir = rootDir.toAbsolutePath().normalize();
    }

    public FileSystemPluginSource(String rootDirPath) {
        this(Path.of(rootDirPath));
    }

    /**
     * Get the underlying filesystem path (for backward compatibility with code
     * that still needs a Path reference).
     */
    public Path getRootDir() {
        return rootDir;
    }

    @Override
    public String getSourceId() {
        return "fs:" + rootDir;
    }

    @Override
    public boolean exists(String relativePath) {
        return Files.exists(resolve(relativePath));
    }

    @Override
    public InputStream readResource(String relativePath) throws IOException {
        return Files.newInputStream(resolve(relativePath));
    }

    @Override
    public String readString(String relativePath) throws IOException {
        return Files.readString(resolve(relativePath), StandardCharsets.UTF_8);
    }

    @Override
    public List<String> listFiles(String relativeDir, String extension) throws IOException {
        Path dir = resolve(relativeDir);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> extension == null || p.getFileName().toString().endsWith(extension))
                    .sorted()
                    .forEach(p -> result.add(relativeDir + "/" + p.getFileName()));
        }
        return result;
    }

    private Path resolve(String relativePath) {
        Path resolved = rootDir.resolve(relativePath).normalize();
        // Security: prevent path traversal
        if (!resolved.startsWith(rootDir)) {
            throw new SecurityException("Path traversal detected: " + relativePath);
        }
        return resolved;
    }
}
