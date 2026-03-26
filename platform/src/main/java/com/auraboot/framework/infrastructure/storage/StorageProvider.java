package com.auraboot.framework.infrastructure.storage;

import com.auraboot.framework.file.constant.StorageType;

import java.io.InputStream;
import java.time.Duration;

/**
 * SPI for pluggable storage backends.
 * Implementations are activated by configuration (e.g. {@code aura.storage.type=minio}).
 */
public interface StorageProvider {

    /** Which storage type this provider handles. */
    StorageType type();

    /**
     * Upload a file.
     *
     * @param key         object key (path within bucket / base dir)
     * @param input       file content stream
     * @param size        file size in bytes
     * @param contentType MIME type
     * @return the storage URL or path
     */
    String upload(String key, InputStream input, long size, String contentType);

    /** Download a file by key. */
    InputStream download(String key);

    /** Delete a file by key. */
    void delete(String key);

    /** Generate a pre-signed (temporary) download URL. */
    String getPresignedUrl(String key, Duration expiry);

    /** Check if a file exists. */
    boolean exists(String key);
}
