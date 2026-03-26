package com.auraboot.framework.meta.entity.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 实例数据配置Bean
 * 用于InstanceEntity的data字段
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InstanceDataBean {
    
    /**
     * 字段数据映射
     */
    private Map<String, Object> fieldValues;
    
    /**
     * 元数据信息
     */
    private MetadataInfo metadata;
    
    /**
     * 状态信息
     */
    private StatusInfo status;
    
    /**
     * 关联数据
     */
    private Map<String, RelationData> relations;
    
    /**
     * 计算字段
     */
    private Map<String, Object> computedFields;
    
    /**
     * 附件信息
     */
    private List<AttachmentInfo> attachments;
    
    /**
     * 审计信息
     */
    private AuditInfo auditInfo;
    
    /**
     * 缓存信息
     */
    private CacheInfo cacheInfo;
    
    /**
     * 扩展属性
     */
    private Map<String, Object> extensions;
    
    /**
     * 元数据信息
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MetadataInfo {
        private String entityType;
        private String entityVersion;
        private String schemaVersion;
        private String dataSource;
        private String createdBy;
        private String updatedBy;
        private String createdAt;
        private String updatedAt;
        private Map<String, String> tags;
        private Map<String, Object> customMetadata;
    }
    
    /**
     * 状态信息
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StatusInfo {
        private String currentStatus;
        private String previousStatus;
        private String statusReason;
        private String statusChangedBy;
        private String statusChangedAt;
        private List<StatusHistory> statusHistory;
        private Map<String, Object> statusMetadata;
        
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class StatusHistory {
            private String fromStatus;
            private String toStatus;
            private String changedBy;
            private String changedAt;
            private String reason;
            private Map<String, Object> context;
        }
    }
    
    /**
     * 关联数据
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RelationData {
        private String relationType; // one-to-one, one-to-many, many-to-many
        private String targetEntity;
        private List<String> targetIds;
        private Map<String, Object> relationMetadata;
        private Boolean isLoaded;
        private String loadStrategy; // lazy, eager
    }
    
    /**
     * 附件信息
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AttachmentInfo {
        private String id;
        private String name;
        private String type;
        private Long size;
        private String url;
        private String storageType; // local, cloud, cdn
        private String mimeType;
        private String checksum;
        private String uploadedBy;
        private String uploadedAt;
        private Map<String, Object> metadata;
    }
    
    /**
     * 审计信息
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AuditInfo {
        private List<AuditRecord> records;
        private String lastAuditedAt;
        private String lastAuditedBy;
        private Map<String, Object> auditMetadata;
        
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class AuditRecord {
            private String action; // create, update, delete, read
            private String fieldName;
            private Object oldValue;
            private Object newValue;
            private String changedBy;
            private String changedAt;
            private String reason;
            private String ipAddress;
            private String userAgent;
        }
    }
    
    /**
     * 缓存信息
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CacheInfo {
        private String cacheKey;
        private String cachedAt;
        private Integer ttl; // 生存时间(秒)
        private String cacheVersion;
        private Boolean isDirty;
        private Map<String, Object> cacheMetadata;
    }
}