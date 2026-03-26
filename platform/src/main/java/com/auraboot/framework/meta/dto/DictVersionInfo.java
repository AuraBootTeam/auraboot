package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 字典版本信息DTO
 * 包含字典的版本历史和当前版本信息
 */
@Data
public class DictVersionInfo {

    /**
     * 字典编码
     */
    private String code;

    /**
     * 字典名称
     */
    private String name;

    /**
     * 当前版本号
     */
    private Integer currentVersion;

    /**
     * 当前语义版本
     */
    private String currentSemver;

    /**
     * 最新版本号
     */
    private Integer latestVersion;

    /**
     * 最新语义版本
     */
    private String latestSemver;

    /**
     * 总版本数
     */
    private Integer totalVersions;

    /**
     * 版本历史列表
     */
    private List<VersionHistoryItem> versionHistory;

    /**
     * 是否有未发布的版本
     */
    private Boolean hasUnpublishedVersions;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 最后更新时间
     */
    private LocalDateTime lastUpdatedAt;

    /**
     * 版本历史项
     */
    @Data
    public static class VersionHistoryItem {
        /**
         * 版本号
         */
        private Integer version;

        /**
         * 语义版本
         */
        private String semver;

        /**
         * 是否为当前版本
         */
        private Boolean isCurrent;

        /**
         * 版本状态
         */
        private String status;

        /**
         * 创建时间
         */
        private LocalDateTime createdAt;

        /**
         * 版本说明
         */
        private String versionNote;
    }
}