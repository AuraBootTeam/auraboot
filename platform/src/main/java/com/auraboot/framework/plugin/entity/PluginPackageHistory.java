package com.auraboot.framework.plugin.entity;

import com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Entity for tracking unified plugin package installation operations.
 * Corresponds to table: ab_plugin_package_history
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ab_plugin_package_history", autoResultMap = true)
public class PluginPackageHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * Unique operation identifier (ULID).
     */
    private String pid;

    /**
     * Tenant ID for multi-tenancy isolation.
     */
    private Long tenantId;

    /**
     * Reference to ab_plugin.pid after successful installation.
     */
    private String pluginPid;

    /**
     * Plugin identifier from manifest.
     */
    private String pluginId;

    /**
     * Plugin namespace.
     */
    private String namespace;

    /**
     * Plugin version.
     */
    private String version;

    /**
     * Human-readable display name.
     */
    private String displayName;

    // ========== Package Source ==========

    /**
     * Source type: UPLOAD, PATH, URL.
     */
    private String sourceType;

    /**
     * Source name (filename or URL).
     */
    private String sourceName;

    /**
     * Path to the extracted package.
     */
    private String packagePath;

    // ========== Config Component ==========

    /**
     * Whether config component is enabled.
     */
    private Boolean configEnabled;

    /**
     * Config import status: PENDING, SUCCESS, FAILED, SKIPPED.
     */
    private String configStatus;

    /**
     * Config import error message.
     */
    private String configError;

    /**
     * Resource counts from config import.
     */
    @TableField(typeHandler = PluginSettingsTypeHandler.class)
    private Map<String, Object> configResourceCounts;

    // ========== Backend Component ==========

    /**
     * Whether backend component is enabled.
     */
    private Boolean backendEnabled;

    /**
     * Backend installation status: PENDING, SUCCESS, FAILED, SKIPPED.
     */
    private String backendStatus;

    /**
     * Backend installation error message.
     */
    private String backendError;

    /**
     * Path to the backend JAR file.
     */
    private String backendJarPath;

    // ========== Frontend Component ==========

    /**
     * Whether frontend component is enabled.
     */
    private Boolean frontendEnabled;

    /**
     * Frontend deployment status: PENDING, SUCCESS, FAILED, SKIPPED.
     */
    private String frontendStatus;

    /**
     * Frontend deployment error message.
     */
    private String frontendError;

    /**
     * URL to the deployed frontend remote entry.
     */
    private String frontendRemoteUrl;

    // ========== Overall Status ==========

    /**
     * Overall installation status.
     */
    private String status;

    /**
     * Error message if installation failed.
     */
    private String errorMessage;

    // ========== Rollback Support ==========

    /**
     * Data needed for rollback (resource PIDs, file paths, etc.).
     */
    @TableField(typeHandler = PluginSettingsTypeHandler.class)
    private Map<String, Object> rollbackData;

    /**
     * Whether this installation can be rolled back.
     */
    private Boolean canRollback;

    // ========== Timestamps ==========

    /**
     * Installation start time.
     */
    private Instant startedAt;

    /**
     * Installation completion time.
     */
    private Instant completedAt;

    /**
     * Record creation time.
     */
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    /**
     * Record update time.
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    /**
     * User who initiated the installation.
     */
    private Long createdBy;

    // ========== Enums ==========

    /**
     * Package installation status.
     */
    public enum PackageStatus {
        PENDING("pending"),
        PARSING("parsing"),
        INSTALLING_CONFIG("installing_config"),
        INSTALLING_BACKEND("installing_backend"),
        INSTALLING_FRONTEND("installing_frontend"),
        SUCCESS("success"),
        FAILED("failed"),
        ROLLING_BACK("rolling_back"),
        ROLLED_BACK("rolled_back");

        private final String code;

        PackageStatus(String code) {
            this.code = code;
        }

        /**
         * Returns the lowercase database value.
         */
        public String code() {
            return code;
        }

        /**
         * Parse from database value (case-insensitive).
         */
        public static PackageStatus fromCode(String code) {
            if (code == null) return null;
            for (PackageStatus s : values()) {
                if (s.code.equalsIgnoreCase(code)) return s;
            }
            return valueOf(code.toUpperCase());
        }
    }

    /**
     * Component status.
     */
    public enum ComponentStatus {
        PENDING("pending"),
        SUCCESS("success"),
        FAILED("failed"),
        SKIPPED("skipped");

        private final String code;

        ComponentStatus(String code) {
            this.code = code;
        }

        /**
         * Returns the lowercase database value.
         */
        public String code() {
            return code;
        }

        /**
         * Parse from database value (case-insensitive).
         */
        public static ComponentStatus fromCode(String code) {
            if (code == null) return null;
            for (ComponentStatus s : values()) {
                if (s.code.equalsIgnoreCase(code)) return s;
            }
            return valueOf(code.toUpperCase());
        }
    }

    /**
     * Source type.
     */
    public enum SourceType {
        UPLOAD,
        PATH,
        URL
    }

    // ========== Helper Methods ==========

    public PackageStatus getStatusEnum() {
        return status != null ? PackageStatus.fromCode(status) : null;
    }

    public void setStatusEnum(PackageStatus statusEnum) {
        this.status = statusEnum != null ? statusEnum.code() : null;
    }

    public ComponentStatus getConfigStatusEnum() {
        return configStatus != null ? ComponentStatus.fromCode(configStatus) : null;
    }

    public void setConfigStatusEnum(ComponentStatus statusEnum) {
        this.configStatus = statusEnum != null ? statusEnum.code() : null;
    }

    public ComponentStatus getBackendStatusEnum() {
        return backendStatus != null ? ComponentStatus.fromCode(backendStatus) : null;
    }

    public void setBackendStatusEnum(ComponentStatus statusEnum) {
        this.backendStatus = statusEnum != null ? statusEnum.code() : null;
    }

    public ComponentStatus getFrontendStatusEnum() {
        return frontendStatus != null ? ComponentStatus.fromCode(frontendStatus) : null;
    }

    public void setFrontendStatusEnum(ComponentStatus statusEnum) {
        this.frontendStatus = statusEnum != null ? statusEnum.code() : null;
    }

    public boolean isInProgress() {
        PackageStatus s = getStatusEnum();
        return s != null && (
            s == PackageStatus.PENDING ||
            s == PackageStatus.PARSING ||
            s == PackageStatus.INSTALLING_CONFIG ||
            s == PackageStatus.INSTALLING_BACKEND ||
            s == PackageStatus.INSTALLING_FRONTEND ||
            s == PackageStatus.ROLLING_BACK
        );
    }

    public boolean isSuccess() {
        return PackageStatus.SUCCESS.code().equals(status);
    }

    public boolean isFailed() {
        return PackageStatus.FAILED.code().equals(status);
    }
}
