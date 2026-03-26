package com.auraboot.framework.infrastructure.storage.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.auraboot.framework.file.constant.StorageType;
import com.auraboot.framework.infrastructure.storage.StorageProperties;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Alibaba Cloud OSS storage provider.
 * Activated when {@code aura.storage.type=oss}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aura.storage.type", havingValue = "oss")
public class OssStorageProvider implements StorageProvider, DisposableBean {

    private final OSS ossClient;
    private final String bucket;

    public OssStorageProvider(StorageProperties properties) {
        StorageProperties.Oss ossConfig = properties.getOss();
        String endpoint = ossConfig.getEndpoint();
        String accessKeyId = ossConfig.getAccessKeyId();
        String accessKeySecret = ossConfig.getAccessKeySecret();
        this.bucket = ossConfig.getBucket();

        this.ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);

        // Ensure the bucket exists
        if (!ossClient.doesBucketExist(bucket)) {
            ossClient.createBucket(bucket);
            log.info("OSS bucket created: {}", bucket);
        }

        log.info("OssStorageProvider initialized: endpoint={}, bucket={}", endpoint, bucket);
    }

    /**
     * Package-private constructor for unit testing with a mocked OSS client.
     */
    OssStorageProvider(OSS ossClient, String bucket) {
        this.ossClient = ossClient;
        this.bucket = bucket;
    }

    @Override
    public StorageType type() {
        return StorageType.OSS;
    }

    @Override
    public String upload(String key, InputStream input, long size, String contentType) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(contentType);
        metadata.setContentLength(size);

        ossClient.putObject(bucket, key, input, metadata);
        log.debug("File uploaded to OSS: bucket={}, key={}", bucket, key);

        return "oss://" + bucket + "/" + key;
    }

    @Override
    public InputStream download(String key) {
        return ossClient.getObject(bucket, key).getObjectContent();
    }

    @Override
    public void delete(String key) {
        ossClient.deleteObject(bucket, key);
        log.debug("File deleted from OSS: bucket={}, key={}", bucket, key);
    }

    @Override
    public String getPresignedUrl(String key, Duration expiry) {
        Date expiration = Date.from(Instant.now().plus(expiry));
        URL url = ossClient.generatePresignedUrl(bucket, key, expiration);
        return url.toString();
    }

    @Override
    public boolean exists(String key) {
        return ossClient.doesObjectExist(bucket, key);
    }

    @Override
    public void destroy() {
        if (ossClient != null) {
            ossClient.shutdown();
            log.info("OSS client shut down");
        }
    }
}
