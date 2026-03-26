package com.auraboot.framework.infrastructure.storage.minio;

import com.auraboot.framework.file.constant.StorageType;
import com.auraboot.framework.infrastructure.storage.StorageProperties;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import io.minio.*;
import io.minio.errors.*;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * MinIO implementation of {@link StorageProvider}.
 * <p>
 * Activated when {@code aura.storage.type=minio} is set in application properties.
 * Requires MinIO server accessible at the configured endpoint.
 * </p>
 *
 * <h3>Configuration example (application.yml):</h3>
 * <pre>
 * aura:
 *   storage:
 *     type: minio
 *     minio:
 *       endpoint: http://localhost:9000
 *       access-key: minioadmin
 *       secret-key: minioadmin
 *       bucket: aura-files
 * </pre>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aura.storage.type", havingValue = "minio")
public class MinioStorageProvider implements StorageProvider, DisposableBean {

    private final MinioClient minioClient;
    private final String bucket;

    public MinioStorageProvider(StorageProperties properties) {
        StorageProperties.Minio minioConfig = properties.getMinio();
        this.bucket = minioConfig.getBucket();

        this.minioClient = MinioClient.builder()
                .endpoint(minioConfig.getEndpoint())
                .credentials(minioConfig.getAccessKey(), minioConfig.getSecretKey())
                .build();

        ensureBucketExists();
        log.info("MinioStorageProvider initialized: endpoint={}, bucket={}",
                minioConfig.getEndpoint(), bucket);
    }

    /**
     * Package-private constructor for testing with a pre-built MinioClient.
     */
    MinioStorageProvider(MinioClient minioClient, String bucket) {
        this.minioClient = minioClient;
        this.bucket = bucket;
    }

    @Override
    public StorageType type() {
        return StorageType.MINIO;
    }

    @Override
    public String upload(String key, InputStream input, long size, String contentType) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(input, size, -1)
                    .contentType(contentType)
                    .build());
            log.debug("File uploaded to MinIO: bucket={}, key={}", bucket, key);
            return key;
        } catch (Exception e) {
            throw new StorageException("Failed to upload file to MinIO: " + key, e);
        }
    }

    @Override
    public InputStream download(String key) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .build());
        } catch (Exception e) {
            throw new StorageException("Failed to download file from MinIO: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .build());
            log.debug("File deleted from MinIO: bucket={}, key={}", bucket, key);
        } catch (Exception e) {
            throw new StorageException("Failed to delete file from MinIO: " + key, e);
        }
    }

    @Override
    public String getPresignedUrl(String key, Duration expiry) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(key)
                    .expiry((int) expiry.getSeconds(), TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            throw new StorageException("Failed to generate pre-signed URL for: " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .build());
            return true;
        } catch (ErrorResponseException e) {
            // Object does not exist
            return false;
        } catch (Exception e) {
            throw new StorageException("Failed to check existence of: " + key, e);
        }
    }

    @Override
    public void destroy() {
        log.info("MinioStorageProvider shutting down (bucket={})", bucket);
        // MinioClient is HTTP-based and has no persistent connections to close
    }

    private void ensureBucketExists() {
        try {
            boolean found = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(bucket)
                    .build());
            if (!found) {
                minioClient.makeBucket(MakeBucketArgs.builder()
                        .bucket(bucket)
                        .build());
                log.info("Created MinIO bucket: {}", bucket);
            }
        } catch (Exception e) {
            throw new StorageException("Failed to ensure MinIO bucket exists: " + bucket, e);
        }
    }

    /**
     * Runtime exception for MinIO storage operations.
     */
    public static class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
