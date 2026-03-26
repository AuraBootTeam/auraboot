package com.auraboot.framework.meta.entity;

import com.auraboot.framework.application.typehandler.JsonNodeTypeHandler;
import com.auraboot.framework.common.constant.StatusConstants;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;

/**
 * 命名查询实体类
 * 对应表：ab_named_query
 * 
 * 该实体用于存储预定义的安全查询模板
 * 支持字段白名单和操作符限制
 */
@Data
@TableName(value = "ab_named_query", autoResultMap = true)
public class NamedQuery {

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
     * 查询唯一标识码
     * 在租户、命名空间、环境范围内唯一
     */
    @TableField("code")
    private String code;

    /**
     * 查询标题
     */
    @TableField("title")
    private String title;

    /**
     * 查询描述
     */
    @TableField("description")
    private String description;

    /**
     * FROM子句SQL
     * 定义查询的数据源 — NULL when using external connector (connector_pid is set)
     */
    @TableField("from_sql")
    private String fromSql;

    /**
     * External API connector PID. When set, this query delegates to the connector
     * instead of running SQL. References ab_api_connector.pid.
     */
    @TableField("connector_pid")
    private String connectorPid;

    /**
     * Endpoint code within the external connector to invoke.
     * Required when connector_pid is set.
     */
    @TableField("connector_endpoint_code")
    private String connectorEndpointCode;

    /**
     * 基础WHERE条件
     * 存储为JSON数组，包含固定的查询条件
     */
    @TableField(value = "base_where", typeHandler = JsonNodeTypeHandler.class, jdbcType = org.apache.ibatis.type.JdbcType.OTHER)
    private JsonNode baseWhere;

    /**
     * 默认排序
     * 存储为JSON对象，定义默认的排序规则
     */
    @TableField(value = "default_order", typeHandler = JsonNodeTypeHandler.class, jdbcType = org.apache.ibatis.type.JdbcType.OTHER)
    private JsonNode defaultOrder;

    /**
     * Query lifecycle status: DRAFT, TESTING, PUBLISHED, DEPRECATED, ARCHIVED
     */
    @TableField("status")
    private String status;

    /**
     * Timestamp when the query was first published
     */
    @TableField("published_at")
    private Instant publishedAt;

    /**
     * User ID who published the query
     */
    @TableField("published_by")
    private Long publishedBy;

    /**
     * Timestamp when the query was deprecated
     */
    @TableField("deprecated_at")
    private Instant deprecatedAt;

    /**
     * Current version number (incremented on each publish)
     */
    @TableField("current_version")
    private Integer currentVersion;

    /**
     * Execution policy (JSONB): maxRows, timeoutMs, rateLimitPerMinute, etc.
     */
    @TableField(value = "policy", typeHandler = com.auraboot.framework.meta.typehandler.NamedQueryPolicyTypeHandler.class, jdbcType = org.apache.ibatis.type.JdbcType.OTHER)
    private NamedQueryPolicy policy;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    /**
     * 构造函数
     */
    public NamedQuery() {
        this.status = StatusConstants.DRAFT;
        this.currentVersion = 0;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * 构造函数
     * @param tenantId 租户ID
     * @param code 查询编码
     * @param title 查询标题
     * @param fromSql FROM子句SQL
     */
    public NamedQuery(Long tenantId,  String code, String title, String fromSql) {
        this();
        this.tenantId = tenantId;
          
        this.code = code;
        this.title = title;
        this.fromSql = fromSql;
    }

    /**
     * Get the parsed status enum.
     */
    public NamedQueryStatus getStatusEnum() {
        return NamedQueryStatus.fromString(status);
    }

    /**
     * Check if enabled (backward compat — delegates to isExecutable).
     */
    public boolean isEnabled() {
        return getStatusEnum().isExecutable();
    }

    /**
     * Check if the query can be executed.
     */
    public boolean isExecutable() {
        return getStatusEnum().isExecutable();
    }

    /**
     * Check if the query definition (from_sql, fields) can be edited.
     */
    public boolean isEditable() {
        return getStatusEnum().isEditable();
    }

    /**
     * Check if the query definition is frozen (PUBLISHED, DEPRECATED, ARCHIVED).
     */
    public boolean isFrozen() {
        return getStatusEnum().isFrozen();
    }

    /**
     * 检查是否有基础WHERE条件
     * @return 是否有基础WHERE条件
     */
    public boolean hasBaseWhere() {
        return baseWhere != null && !baseWhere.isNull() && baseWhere.size() > 0;
    }

    /**
     * 检查是否有默认排序
     * @return 是否有默认排序
     */
    public boolean hasDefaultOrder() {
        return defaultOrder != null && !defaultOrder.isNull() && defaultOrder.size() > 0;
    }

    /**
     * 获取显示名称
     * @return 显示名称
     */
    public String getDisplayName() {
        return title != null && !title.trim().isEmpty() ? title : code;
    }

    /**
     * Whether this query uses an external REST API connector instead of SQL.
     */
    public boolean isConnectorType() {
        return connectorPid != null && !connectorPid.isBlank();
    }

    /**
     * 检查查询定义是否有效
     * @return 是否有效
     */
    public boolean isValid() {
        if (tenantId == null || code == null || code.trim().isEmpty()) {
            return false;
        }
        // Either SQL-based or connector-based
        if (isConnectorType()) {
            return connectorEndpointCode != null && !connectorEndpointCode.isBlank();
        }
        return fromSql != null && !fromSql.trim().isEmpty();
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

    /**
     * 获取查询类型（从FROM SQL推断）
     * @return 查询类型
     */
    public String getQueryType() {
        if (fromSql == null || fromSql.trim().isEmpty()) {
            return "unknown";
        }
        
        String sql = fromSql.trim().toLowerCase();
        if (sql.startsWith("select")) {
            return "subquery";
        } else if (sql.contains("join")) {
            return "join";
        } else if (sql.contains("union")) {
            return "union";
        } else {
            return "table";
        }
    }

    /**
     * 检查是否为复杂查询
     * @return 是否为复杂查询
     */
    public boolean isComplexQuery() {
        if (fromSql == null) {
            return false;
        }
        
        String sql = fromSql.toLowerCase();
        return sql.contains("join") || sql.contains("union") || sql.contains("subquery") || sql.contains("with");
    }

    /**
     * 获取查询摘要信息
     * @return 查询摘要
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("查询: ").append(getDisplayName());
        sb.append(", 类型: ").append(getQueryType());
        sb.append(", 状态: ").append(isEnabled() ? "启用" : "禁用");
        if (hasBaseWhere()) {
            sb.append(", 有基础条件");
        }
        if (hasDefaultOrder()) {
            sb.append(", 有默认排序");
        }
        return sb.toString();
    }
}
