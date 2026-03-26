package com.auraboot.framework.meta.entity;

import com.auraboot.framework.application.database.mybatis.DataSourceItemBeanTypeHandler;
import com.auraboot.framework.application.database.mybatis.ExtensionTypeHandler;
import com.auraboot.framework.meta.entity.payload.DataSourceItemBean;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;
import java.util.List;
import com.auraboot.framework.common.constant.StatusConstants;
/**
 * 字典主表实体类
 * 对应表：ab_dict
 *
 * 该实体用于存储字典的基本信息、版本信息和小字典数据
 * 支持版本控制
 */
@Data
@TableName(value = "ab_dict", autoResultMap = true)
public class Dict {

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 业务主键(ULID)
     */
    @TableField("pid")
    private String pid;

    /**
     * 租户ID
     */
    @TableField("tenant_id")
    private Long tenantId;

      

    

    /**
     * 字典唯一标识码
     * 在租户、命名空间、环境范围内唯一
     */
    @TableField("code")
    private String code;

    /**
     * 字典名称
     */
    @TableField("name")
    private String name;

    /**
     * 字典描述
     */
    @TableField("description")
    private String description;

    /**
     * 字典类型
     * DYNAMIC: 普通字典（默认类型，数据存储在 dict_item 表）
     * TREE: 树形字典（支持层级结构，使用 parent_value 字段）
     */
    @TableField("dict_type")
    private String dictType;

    /**
     * 字典状态
     * DRAFT: 草稿
     * PUBLISHED: 已发布
     * DEPRECATED: 已废弃
     * ARCHIVED: 已归档
     * DISABLED: 已禁用
     */
    @TableField("status")
    private String status;

    /**
     * 版本号
     */
    @TableField("version")
    private Integer version;

    /**
     * 语义化版本
     */
    @TableField("semver")
    private String semver;

    /**
     * 是否为当前版本
     */
    @TableField("is_current")
    private Boolean isCurrent;

    /**
     * 小字典数据直接存储（已废弃，保留字段兼容性）
     * 所有字典项统一存储在 ab_dict_item 表
     */
    @TableField(value = "items", typeHandler = DataSourceItemBeanTypeHandler.class, jdbcType = JdbcType.OTHER)
    private List<DataSourceItemBean> items;

    /**
     * 扩展属性
     */
    @TableField(value = "extension", typeHandler = ExtensionTypeHandler.class, jdbcType = JdbcType.OTHER)
    private ExtensionBean extension;

    /**
     * 插件PID（用于标识资源来源的插件）
     */
    @TableField("plugin_pid")
    private String pluginPid;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private Instant createdAt;

    /**
     * 更新时间
     */
    @TableField("updated_at")
    private Instant updatedAt;

    /**
     * 构造函数
     */
    public Dict() {
        this.status = "draft";
        this.version = 1;
        this.isCurrent = true;
        this.dictType = "dynamic"; // 默认类型
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * 检查是否为普通字典
     * @return 是否为普通字典
     */
    public boolean isDynamicDict() {
        return "dynamic".equalsIgnoreCase(dictType);
    }

    /**
     * 检查是否为树形字典
     * @return 是否为树形字典
     */
    public boolean isTreeDict() {
        return "tree".equals(dictType) || "cascade".equals(dictType);
    }

    /**
     * 检查是否为静态字典（已废弃）
     * @deprecated 所有字典统一使用 DYNAMIC 类型
     * @return 始终返回 false
     */
    @Deprecated
    public boolean isStaticDict() {
        return "static".equalsIgnoreCase(dictType);
    }

    /**
     * 检查是否为级联字典（已废弃，使用 TREE 代替）
     * @deprecated 使用 isTreeDict() 代替
     * @return 是否为级联字典
     */
    @Deprecated
    public boolean isCascadeDict() {
        return "cascade".equals(dictType);
    }

    /**
     * 检查是否为当前版本
     * @return 是否为当前版本
     */
    public boolean isCurrentVersion() {
        return Boolean.TRUE.equals(isCurrent);
    }

    /**
     * 检查是否为草稿状态
     * @return 是否为草稿状态
     */
    public boolean isDraft() {
        return StatusConstants.DRAFT.equals(status);
    }

    /**
     * 检查是否已发布
     * @return 是否已发布
     */
    public boolean isPublished() {
        return StatusConstants.PUBLISHED.equals(status);
    }

    /**
     * 检查是否已废弃
     * @return 是否已废弃
     */
    public boolean isDeprecated() {
        return StatusConstants.DEPRECATED.equals(status);
    }

    /**
     * 检查是否已归档
     * @return 是否已归档
     */
    public boolean isArchived() {
        return StatusConstants.ARCHIVED.equals(status);
    }

    /**
     * 检查是否已禁用
     * @return 是否已禁用
     */
    public boolean isDisabled() {
        return StatusConstants.DISABLED.equals(status);
    }

    /**
     * 检查是否处于活跃状态（可用于业务逻辑）
     * @return 是否活跃
     */
    public boolean isActive() {
        return isPublished() || isDeprecated();
    }

    /**
     * 检查是否可编辑
     * @return 是否可编辑
     */
    public boolean isEditable() {
        return isDraft();
    }


    /**
     * 获取字典显示名称
     * @return 显示名称
     */
    public String getDisplayName() {
        return name != null ? name : code;
    }

    /**
     * 获取版本显示字符串
     * @return 版本显示字符串
     */
    public String getVersionDisplay() {
        if (semver != null && !semver.trim().isEmpty()) {
            return semver;
        }
        return "v" + (version != null ? version : 1);
    }

    /**
     * 获取完整标识
     * @return 完整标识（包含命名空间和环境）
     */
    public String getFullCode() {
        StringBuilder sb = new StringBuilder();


        sb.append(code);
        return sb.toString();
    }
}