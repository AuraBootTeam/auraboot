package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询权限检查结果
 *
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryAccessCheckResult {

    /**
     * 是否有权限
     */
    @Builder.Default
    private Boolean hasAccess = false;

    /**
     * 是否允许查询
     */
    @Builder.Default
    private Boolean allowed = false;

    /**
     * 拒绝原因
     */
    private String denyReason;

    /**
     * 权限检查详情列表
     */
    @Builder.Default
    private List<AccessCheckDetail> details = new ArrayList<>();

    /**
     * 被拒绝的字段列表
     */
    @Builder.Default
    private List<String> deniedFields = new ArrayList<>();

    /**
     * 被拒绝的操作列表
     */
    @Builder.Default
    private List<String> deniedOperations = new ArrayList<>();

    /**
     * 权限检查上下文
     */
    @Builder.Default
    private Map<String, Object> accessContext = new HashMap<>();

    /**
     * 权限检查耗时(毫秒)
     */
    private Long checkTimeMs;

    public Map<String, Object> getPermissionContext() {
        if (accessContext == null) {
            accessContext = new HashMap<>();
        }
        return accessContext;
    }

    public List<AccessCheckDetail> getDetails() {
        if (details == null) {
            details = new ArrayList<>();
        }
        return details;
    }

    public Boolean getHasPermission() {
        return hasAccess != null && hasAccess;
    }

    /**
     * 权限检查详情
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AccessCheckDetail {
        /**
         * 资源标识
         */
        private String resource;

        /**
         * 操作类型
         */
        private String operation;

        /**
         * 是否允许
         */
        private Boolean allowed;

        /**
         * 原因说明
         */
        private String reason;
    }
}
