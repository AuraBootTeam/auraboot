package com.auraboot.framework.governance.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.auraboot.framework.application.database.mybatis.JsonbMapTypeHandler;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.util.Date;
import java.util.Map;

/**
 * Master data version entity - stores versioned snapshots of entity records.
 */
@Data
@TableName(value = "ns_governance_version", autoResultMap = true)
public class MasterDataVersion {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String pid;

    private Long tenantId;

    /** The entity type (model code) being versioned */
    private String entityType;

    /** The row PID of the versioned record */
    private String entityPid;

    /** Monotonically increasing version number per entity */
    private Integer versionNumber;

    /** JSONB snapshot of the record data at this version */
    @TableField(value = "snapshot_data", jdbcType = JdbcType.OTHER, typeHandler = JsonbMapTypeHandler.class)
    private Map<String, Object> snapshotData;

    /** PID of the change request that created this version (nullable for initial seed) */
    private String changeRequestPid;

    /** User PID who created this version */
    private String createdByPid;

    /** Optional comment describing this version */
    private String comment;

    private Date createdAt;
}
