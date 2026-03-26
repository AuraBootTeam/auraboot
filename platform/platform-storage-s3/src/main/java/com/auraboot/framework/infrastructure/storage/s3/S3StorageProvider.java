package com.auraboot.framework.infrastructure.storage.s3;

import com.auraboot.framework.file.constant.StorageType;
import com.auraboot.framework.infrastructure.storage.StorageProperties;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.InputStream;
import java.time.Duration;

/**
 * AWS S3 storage provider implementation.
 * Activated when {@code aura.storage.type=s3}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aura.storage.type", havingValue = "s3")
public class S3StorageProvider implements StorageProvider, DisposableBean {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final String bucket;

    public S3StorageProvider(StorageProperties properties) {
        StorageProperties.S3 s3Config = properties.getS3();
        this.bucket = s3Config.getBucket();

        Region region = Region.of(s3Config.getRegion());
        StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(s3Config.getAccessKeyId(), s3Config.getSecretAccessKey())
        );

        this.s3Client = S3Client.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build();

        this.s3Presigner = S3Presigner.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build();

        ensureBucketExists();
        log.info("S3StorageProvider initialized: region={}, bucket={}", s3Config.getRegion(), bucket);
    }

    /**
     * Package-private constructor for testing with pre-built clients.
     */
    S3StorageProvider(S3Client s3Client, S3Presigner s3Presigner, String bucket) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.bucket = bucket;
    }

    private void ensureBucketExists() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            log.debug("S3 bucket already exists: {}", bucket);
        } catch (NoSuchBucketException e) {
            log.info("S3 bucket does not exist, creating: {}", bucket);
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
    }

    @Override
    public StorageType type() {
        return StorageType.S3;
    }

    @Override
    public String upload(String key, InputStream input, long size, String contentType) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build();
        s3Client.putObject(request, RequestBody.fromInputStream(input, size));
        String url = String.format("s3://%s/%s", bucket, key);
        log.debug("File uploaded to S3: {}", url);
        return url;
    }

    @Override
    public InputStream download(String key) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        return s3Client.getObject(request);
    }

    @Override
    public void delete(String key) {
        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        s3Client.deleteObject(request);
        log.debug("File deleted from S3: s3://{}/{}", bucket, key);
    }

    @Override
    public String getPresignedUrl(String key, Duration expiry) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build())
                .build();
        String url = s3Presigner.presignGetObject(presignRequest).url().toString();
        log.debug("Pre-signed URL generated for key={}, expiry={}", key, expiry);
        return url;
    }

    @Override
    public boolean exists(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    @Override
    public void destroy() {
        log.info("Shutting down S3StorageProvider...");
        if (s3Client != null) {
            s3Client.close();
        }
        if (s3Presigner != null) {
            s3Presigner.close();
        }
    }
}
