package com.auraboot.framework.common.util;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Centralized path validation helpers for filesystem operations that accept
 * config, upload metadata, or admin-supplied paths.
 */
public final class PathSafetyUtils {

    private PathSafetyUtils() {
    }

    public static Path normalizeAbsolute(Path path, String context) {
        if (path == null) {
            throw new IllegalArgumentException(context + " must not be null");
        }
        return path.toAbsolutePath().normalize();
    }

    public static Path requireExistingDirectory(Path path, String context) {
        Path normalized = normalizeAbsolute(path, context);
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException(context + " is not a directory: " + normalized);
        }
        return normalized;
    }

    public static Path requireSafeChild(Path baseDir, String relativePath, String context) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException(context + " must not be blank");
        }
        Path child = Path.of(relativePath);
        if (child.isAbsolute()) {
            throw new IllegalArgumentException(context + " must be relative: " + relativePath);
        }
        if (relativePath.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(context + " contains a NUL byte");
        }
        Path base = normalizeAbsolute(baseDir, context + " baseDir");
        Path resolved = base.resolve(child).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException(context + " escapes base directory: " + relativePath);
        }
        return resolved;
    }

    public static Path requireSafeChild(Path baseDir, Path childPath, String context) {
        if (childPath == null) {
            throw new IllegalArgumentException(context + " must not be null");
        }
        return requireSafeChild(baseDir, childPath.toString(), context);
    }

    public static Path requireWithinBase(Path baseDir, Path candidatePath, String context) {
        if (candidatePath == null) {
            throw new IllegalArgumentException(context + " must not be null");
        }
        Path base = normalizeAbsolute(baseDir, context + " baseDir");
        Path candidate = candidatePath.isAbsolute()
                ? candidatePath.normalize()
                : base.resolve(candidatePath).normalize();
        if (!candidate.startsWith(base)) {
            throw new IllegalArgumentException(context + " escapes base directory: " + candidatePath);
        }
        return candidate;
    }

    public static String requireSafeFileName(String fileName, String requiredSuffix, String context) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException(context + " must not be blank");
        }
        if (fileName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(context + " contains a NUL byte");
        }
        String leaf = Path.of(fileName).getFileName().toString();
        if (!leaf.equals(fileName) || fileName.contains("/") || fileName.contains("\\")) {
            throw new IllegalArgumentException(context + " must be a file name, not a path: " + fileName);
        }
        if (".".equals(fileName) || "..".equals(fileName)) {
            throw new IllegalArgumentException(context + " must be a regular file name: " + fileName);
        }
        if (requiredSuffix != null && !fileName.toLowerCase().endsWith(requiredSuffix.toLowerCase())) {
            throw new IllegalArgumentException(context + " must end with " + requiredSuffix);
        }
        return fileName;
    }
}
