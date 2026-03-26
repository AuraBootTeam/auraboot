package com.auraboot.framework.meta.dto;

import lombok.Data;
import java.util.List;
import java.util.ArrayList;

/**
 * 绑定关系兼容性检查结果DTO
 * 
 * @author AuraBoot Framework
 * @since 2.0.0
 */
@Data
public class BindingCompatibilityResult {

    /**
     * 是否兼容
     */
    private Boolean compatible;

    /**
     * 兼容性级别
     */
    private CompatibilityLevel level;

    /**
     * 检查消息
     */
    private String message;

    /**
     * 兼容性问题列表
     */
    private List<CompatibilityIssue> issues;

    /**
     * 建议的解决方案
     */
    private List<String> suggestions;

    /**
     * 检查详情
     */
    private CompatibilityDetails details;

    /**
     * 扩展信息
     */
    private Object extension;

    /**
     * 构造函数
     */
    public BindingCompatibilityResult() {
        this.compatible = true;
        this.level = CompatibilityLevel.FULL;
        this.issues = new ArrayList<>();
        this.suggestions = new ArrayList<>();
    }

    /**
     * 兼容性级别
     */
    public enum CompatibilityLevel {
        /**
         * 完全兼容
         */
        FULL,

        /**
         * 部分兼容
         */
        PARTIAL,

        /**
         * 不兼容
         */
        INCOMPATIBLE,

        /**
         * 未知
         */
        UNKNOWN
    }

    /**
     * 兼容性问题
     */
    @Data
    public static class CompatibilityIssue {
        /**
         * 问题类型
         */
        private IssueType type;

        /**
         * 问题级别
         */
        private IssueLevel level;

        /**
         * 问题描述
         */
        private String description;

        /**
         * 影响范围
         */
        private String impact;

        /**
         * 相关字段
         */
        private String code;

        /**
         * 详细信息
         */
        private Object details;

        /**
         * 问题类型
         */
        public enum IssueType {
            TYPE_MISMATCH,      // 类型不匹配
            VERSION_CONFLICT,   // 版本冲突
            CONSTRAINT_VIOLATION, // 约束违反
            DEPENDENCY_MISSING,  // 依赖缺失
            CONFIGURATION_ERROR  // 配置错误
        }

        /**
         * 问题级别
         */
        public enum IssueLevel {
            ERROR,    // 错误
            WARNING,  // 警告
            INFO      // 信息
        }
    }

    /**
     * 兼容性检查详情
     */
    @Data
    public static class CompatibilityDetails {
        /**
         * 模型版本
         */
        private Integer modelVersion;

        /**
         * 字段版本
         */
        private Integer fieldVersion;

        /**
         * 检查的规则数量
         */
        private Integer checkedRules;

        /**
         * 通过的规则数量
         */
        private Integer passedRules;

        /**
         * 失败的规则数量
         */
        private Integer failedRules;

        /**
         * 检查时间
         */
        private Long checkTime;
    }

    /**
     * 添加兼容性问题
     */
    public void addIssue(CompatibilityIssue issue) {
        this.issues.add(issue);
        if (issue.getLevel() == CompatibilityIssue.IssueLevel.ERROR) {
            this.compatible = false;
            this.level = CompatibilityLevel.INCOMPATIBLE;
        } else if (issue.getLevel() == CompatibilityIssue.IssueLevel.WARNING && this.level == CompatibilityLevel.FULL) {
            this.level = CompatibilityLevel.PARTIAL;
        }
    }

    /**
     * 添加建议
     */
    public void addSuggestion(String suggestion) {
        this.suggestions.add(suggestion);
    }

    /**
     * 检查是否有错误级别的问题
     */
    public boolean hasErrors() {
        return issues.stream().anyMatch(issue -> issue.getLevel() == CompatibilityIssue.IssueLevel.ERROR);
    }

    /**
     * 检查是否有警告级别的问题
     */
    public boolean hasWarnings() {
        return issues.stream().anyMatch(issue -> issue.getLevel() == CompatibilityIssue.IssueLevel.WARNING);
    }
}