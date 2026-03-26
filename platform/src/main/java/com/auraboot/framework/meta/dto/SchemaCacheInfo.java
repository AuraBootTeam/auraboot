package com.auraboot.framework.meta.dto;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Schema缓存信息DTO
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
public class SchemaCacheInfo {
    
    /**
     * 缓存键
     */
    private String cacheKey;
    
    /**
     * 租户ID
     */
    private Long tenantId;
    
    /**
     * 实体代码
     */
    private String entityCode;
    
    /**
     * Schema类型
     */
    private String schemaType;
    
    /**
     * 缓存版本
     */
    private String version;
    
    /**
     * 缓存大小（字节）
     */
    private Long cacheSize;
    
    /**
     * 命中次数
     */
    private Long hitCount;
    
    /**
     * 未命中次数
     */
    private Long missCount;
    
    /**
     * 最后访问时间
     */
    private LocalDateTime lastAccessTime;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
    
    /**
     * 过期时间
     */
    private LocalDateTime expireAt;
    
    /**
     * 是否已过期
     */
    private Boolean expired;
    
    /**
     * 缓存状态
     */
    private String status;
    
    /**
     * 扩展属性
     */
    private Map<String, Object> metadata;
    
    // getters and setters
    public String getCacheKey() {
        return cacheKey;
    }
    
    public void setCacheKey(String cacheKey) {
        this.cacheKey = cacheKey;
    }
    
    public Long getTenantId() {
        return tenantId;
    }
    
    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }
    
    public String getEntityCode() {
        return entityCode;
    }
    
    public void setEntityCode(String entityCode) {
        this.entityCode = entityCode;
    }
    
    public String getSchemaType() {
        return schemaType;
    }
    
    public void setSchemaType(String schemaType) {
        this.schemaType = schemaType;
    }
    
    public String getVersion() {
        return version;
    }
    
    public void setVersion(String version) {
        this.version = version;
    }
    
    public Long getCacheSize() {
        return cacheSize;
    }
    
    public void setCacheSize(Long cacheSize) {
        this.cacheSize = cacheSize;
    }
    
    public Long getHitCount() {
        return hitCount;
    }
    
    public void setHitCount(Long hitCount) {
        this.hitCount = hitCount;
    }
    
    public Long getMissCount() {
        return missCount;
    }
    
    public void setMissCount(Long missCount) {
        this.missCount = missCount;
    }
    
    public LocalDateTime getLastAccessTime() {
        return lastAccessTime;
    }

    public void setLastAccessTime(LocalDateTime lastAccessTime) {
        this.lastAccessTime = lastAccessTime;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(LocalDateTime expireAt) {
        this.expireAt = expireAt;
    }
    
    public Boolean getExpired() {
        return expired;
    }
    
    public void setExpired(Boolean expired) {
        this.expired = expired;
    }
    
    public String getStatus() {
        return status;
    }
    
    public void setStatus(String status) {
        this.status = status;
    }
    
    public Map<String, Object> getMetadata() {
        return metadata;
    }
    
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
    
    /**
     * 计算命中率
     */
    public double getHitRate() {
        long total = hitCount + missCount;
        return total == 0 ? 0.0 : (double) hitCount / total;
    }
    
    /**
     * 检查是否已过期
     */
    public boolean isExpired() {
        if (expired != null && expired) {
            return true;
        }
        if (expireAt != null) {
            return LocalDateTime.now(ZoneOffset.UTC).isAfter(expireAt);
        }
        return false;
    }
}