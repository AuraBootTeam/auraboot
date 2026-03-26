package com.auraboot.framework.infrastructure.storage.local;

import com.auraboot.framework.file.constant.StorageType;
import com.auraboot.framework.infrastructure.storage.StorageProperties;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * Local filesystem storage provider.
 * Default fallback when no cloud provider is configured.
 */
@Slf4j
public class LocalStorageProvider implements StorageProvider {

    private final Path basePath;

    public LocalStorageProvider(StorageProperties properties) {
        this.basePath = Paths.get(properties.getLocal().getBasePath()).toAbsolutePath().normalize();
        log.info("LocalStorageProvider initialized: basePath={}", basePath);
    }

    @Override
    public StorageType type() {
        return StorageType.LOCAL;
    }

    @Override
    public String upload(String key, InputStream input, long size, String contentType) {
        Path filePath = resolveAndValidate(key);
        try {
            Files.createDirectories(filePath.getParent());
            Files.copy(input, filePath);
            log.debug("File uploaded to local storage: {}", filePath);
            return filePath.toString();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to upload file to local storage: " + key, e);
        }
    }

    @Override
    public InputStream download(String key) {
        Path filePath = resolveAndValidate(key);
        try {
            return Files.newInputStream(filePath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to download file from local storage: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        Path filePath = resolveAndValidate(key);
        try {
            Files.deleteIfExists(filePath);
            log.debug("File deleted from local storage: {}", filePath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete file from local storage: " + key, e);
        }
    }

    @Override
    public String getPresignedUrl(String key, Duration expiry) {
        resolveAndValidate(key);
        // Local storage does not support pre-signed URLs; return a relative key
        // (never expose server-side absolute paths to clients)
        return "/api/files/download?key=" + key;
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(resolveAndValidate(key));
    }

    /**
     * Resolve key against basePath and validate no path traversal occurs.
     */
    private Path resolveAndValidate(String key) {
        Path resolved = basePath.resolve(key).normalize();
        if (!resolved.startsWith(basePath)) {
            throw new SecurityException("Path traversal detected: " + key);
        }
        return resolved;
    }
}
