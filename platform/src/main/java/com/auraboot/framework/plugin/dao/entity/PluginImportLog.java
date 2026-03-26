package com.auraboot.framework.plugin.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Plugin import history log entity.
 * Records every import attempt with status, imported resources, and error details.
 */
@Data
@TableName(value = "ab_plugin_import_log", autoResultMap = true)
public class PluginImportLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String pid;

    private Long tenantId;

    private String pluginCode;

    private String pluginVersion;

    /**
     * Import status: SUCCESS, FAILED, ROLLED_BACK
     */
    private String status;

    /**
     * JSONB column storing list of imported resource references.
     * Each entry: { "type": "SCHEMA|PERMISSION|MENU", "id": 123, "code": "xxx" }
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> importedResources;

    private String errorMessage;

    private Instant startedAt;

    private Instant completedAt;

    private Long createdBy;

    private Instant createdAt;

    private Instant updatedAt;

    private Boolean deletedFlag;
}
