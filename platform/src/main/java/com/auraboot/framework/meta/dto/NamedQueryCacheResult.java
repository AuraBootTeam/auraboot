package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 命名查询缓存操作结果DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryCacheResult {

    /**
     * 操作是否成功
     */
    private Boolean success;

      

    

    /**
     * 预热的查询数量
     */
    private Integer warmedUpCount;

    /**
     * 清除的缓存数量
     */
    private Integer clearedCount;

    /**
     * 刷新的缓存数量
     */
    private Integer refreshedCount;

    /**
     * 操作开始时间
     */
    private LocalDateTime startTime;

    /**
     * 操作结束时间
     */
    private LocalDateTime endTime;

    /**
     * 操作耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 操作详情
     */
    private String details;

    /**
     * 缓存命中率
     */
    private Double hitRate;

    /**
     * 缓存大小
     */
    private Long cacheSize;

    /**
     * 构造函数
     */
    public NamedQueryCacheResult() {
        this.success = false;
        this.warmedUpCount = 0;
        this.clearedCount = 0;
        this.refreshedCount = 0;
    }

    /**
     * 获取操作摘要
     * @return 操作摘要
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("缓存操作");
        if (success) {
            sb.append("成功");
        } else {
            sb.append("失败");
        }
        if (warmedUpCount > 0) {
            sb.append("，预热").append(warmedUpCount).append("个查询");
        }
        if (clearedCount > 0) {
            sb.append("，清除").append(clearedCount).append("个缓存");
        }
        if (refreshedCount > 0) {
            sb.append("，刷新").append(refreshedCount).append("个缓存");
        }
        if (durationMs != null) {
            sb.append("，耗时").append(durationMs).append("毫秒");
        }
        return sb.toString();
    }
}