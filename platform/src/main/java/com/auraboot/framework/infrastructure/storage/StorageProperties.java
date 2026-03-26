package com.auraboot.framework.infrastructure.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the storage abstraction layer.
 */
@Data
@ConfigurationProperties(prefix = "aura.storage")
public class StorageProperties {

    /** Storage provider type: local | minio | oss | s3. */
    private String type = "local";

    /** Whether to isolate files by tenant ID in the key path. */
    private boolean tenantIsolated = true;

    private Local local = new Local();
    private Minio minio = new Minio();
    private Oss oss = new Oss();
    private S3 s3 = new S3();
    private Cdn cdn = new Cdn();

    @Data
    public static class Local {
        /** Base directory for local file storage. */
        private String basePath = "/data/files";
    }

    @Data
    public static class Minio {
        private String endpoint = "http://localhost:9000";
        private String accessKey;
        private String secretKey;
        private String bucket = "aura-files";
    }

    @Data
    public static class Oss {
        private String endpoint = "https://oss-cn-hangzhou.aliyuncs.com";
        private String accessKeyId;
        private String accessKeySecret;
        private String bucket = "aura-files";
    }

    @Data
    public static class S3 {
        private String region = "us-east-1";
        private String accessKeyId;
        private String secretAccessKey;
        private String bucket = "aura-files";
    }

    @Data
    public static class Cdn {
        /** CDN base URL. When set, download URLs are rewritten through CDN. */
        private String baseUrl;
    }
}
