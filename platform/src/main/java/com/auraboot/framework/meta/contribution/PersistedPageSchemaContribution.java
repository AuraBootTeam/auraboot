package com.auraboot.framework.meta.contribution;

import com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;
import java.util.Map;

/** Persistence row for a plugin-owned page contribution. */
@Data
@TableName(value = "ab_page_schema_contribution", autoResultMap = true)
public class PersistedPageSchemaContribution {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String pid;
    private Long tenantId;
    private Long envId;
    private String pluginPid;
    @TableField(exist = false)
    private String contributorId;
    private String pluginVersion;
    private String contributionId;
    private String targetPageKey;
    private String slotId;
    private String kind;
    private Integer priority;
    @TableField(typeHandler = PluginSettingsTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Map<String, Object> payload;
    private Boolean active;
    private Boolean deletedFlag;
    private Instant createdAt;
    private Instant updatedAt;
}
