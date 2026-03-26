package com.auraboot.framework.meta.entity;

import com.auraboot.framework.application.typehandler.JsonNodeTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * 字典项实体类
 * 对应表：ab_dict_item
 * 
 * 该实体用于存储大字典和级联字典的具体数据项
 * 支持层级结构和排序
 */
@Data
@TableName(value = "ab_dict_item", autoResultMap = true)
public class DictItem {

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
     * 字典ID
     * 直接关联到字典版本
     */
    @TableField("dict_id")
    private Long dictId;

    /**
     * 字典项值
     * 用于程序逻辑处理
     */
    @TableField("value")
    private String value;

    /**
     * 字典项标签
     * 用于界面显示
     */
    @TableField("label")
    private String label;

    /**
     * 父级值
     * 支持层级结构，为null表示顶级项
     */
    @TableField("parent_value")
    private String parentValue;

    /**
     * 排序号
     * 用于控制字典项的显示顺序
     */
    @TableField("sort_no")
    private Integer sortNo;

    /**
     * 状态
     * ENABLED: 启用
     * DISABLED: 禁用
     */
    @TableField("status")
    private String status;

    /**
     * Data source: PLUGIN (created by plugin import) or USER (created by user)
     */
    @TableField("source")
    private String source;

    /**
     * 扩展属性
     * 存储额外的字典项属性
     */
    @TableField(value = "extra", typeHandler = JsonNodeTypeHandler.class, jdbcType = JdbcType.OTHER)
    private JsonNode extra;

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
    public DictItem() {
        this.status = "enabled";
        this.source = "user";
        this.sortNo = 0;

        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * 构造函数
     * @param dictId 字典ID
     * @param value 字典项值
     * @param label 字典项标签
     */
    public DictItem(Long dictId, String value, String label) {
        this();
        this.dictId = dictId;
        this.value = value;
        this.label = label;
    }

    /**
     * 构造函数
     * @param dictId 字典ID
     * @param value 字典项值
     * @param label 字典项标签
     * @param parentValue 父级值
     * @param sortNo 排序号
     */
    public DictItem(Long dictId, String value, String label, String parentValue, Integer sortNo) {
        this(dictId, value, label);
        this.parentValue = parentValue;
        this.sortNo = sortNo != null ? sortNo : 0;
    }

    /**
     * 检查是否为顶级项
     * @return 是否为顶级项
     */
    public boolean isTopLevel() {
        return parentValue == null || parentValue.trim().isEmpty();
    }

    /**
     * 检查是否为子项
     * @return 是否为子项
     */
    public boolean isChildItem() {
        return !isTopLevel();
    }

    /**
     * 检查是否启用
     * @return 是否启用
     */
    public boolean isEnabled() {
        return StatusConstants.ENABLED.equals(status);
    }

    /**
     * 获取显示文本
     * @return 显示文本
     */
    public String getDisplayText() {
        return label != null ? label : value;
    }

    /**
     * 获取排序值，确保不为null
     * @return 排序值
     */
    public Integer getSortNo() {
        return sortNo != null ? sortNo : 0;
    }

    /**
     * 设置排序值，确保不为负数
     * @param sortNo 排序值
     */
    public void setSortNo(Integer sortNo) {
        this.sortNo = sortNo != null && sortNo >= 0 ? sortNo : 0;
    }

    /**
     * 检查是否有扩展属性
     * @return 是否有扩展属性
     */
    public boolean hasExtra() {
        return extra != null && !extra.isNull() && extra.size() > 0;
    }

    /**
     * 从扩展属性中获取值
     * @param key 属性键
     * @return 属性值
     */
    public String getExtraValue(String key) {
        if (hasExtra() && extra.has(key)) {
            JsonNode node = extra.get(key);
            return node.isNull() ? null : node.asText();
        }
        return null;
    }

    /**
     * 获取层级深度（根据父级值计算）
     * @return 层级深度，顶级为0
     */
    public int getLevel() {
        if (isTopLevel()) {
            return 0;
        }
        // 简单实现，实际可能需要递归计算
        return 1;
    }

    /**
     * 生成用于排序的键
     * @return 排序键
     */
    public String getSortKey() {
        StringBuilder sb = new StringBuilder();
        if (parentValue != null) {
            sb.append(parentValue).append("-");
        }
        sb.append(String.format("%06d", getSortNo()));
        return sb.toString();
    }
}