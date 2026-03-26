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
 * Master data change request entity - implements an approval workflow
 * for changes to governed entity records.
 */
@Data
@TableName(value = "ns_governance_change_request", autoResultMap = true)
public class MasterDataChangeRequest {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String pid;

    private Long tenantId;

    /** Auto-generated request number, e.g. CR-2026-0001 */
    private String requestNumber;

    /** The entity type (model code) being changed */
    private String entityType;

    /** The row PID of the record being changed */
    private String entityPid;

    /** Type of change: CREATE, UPDATE, DELETE, BULK_UPDATE */
    private String changeType;

    /** JSONB payload of the proposed changes */
    @TableField(value = "proposed_data", jdbcType = JdbcType.OTHER, typeHandler = JsonbMapTypeHandler.class)
    private Map<String, Object> proposedData;

    /** JSONB snapshot of the original data before change (for UPDATE/DELETE) */
    @TableField(value = "original_data", jdbcType = JdbcType.OTHER, typeHandler = JsonbMapTypeHandler.class)
    private Map<String, Object> originalData;

    /** Status: DRAFT, PENDING_REVIEW, APPROVED, APPLIED, REJECTED, CANCELLED */
    private String status;

    /** PID of the user who submitted the request */
    private String submittedByPid;

    /** PID of the user who reviewed the request */
    private String reviewedByPid;

    /** Reason provided by the reviewer */
    private String reviewComment;

    /** PID of the user who applied the approved change */
    private String appliedByPid;

    private Date createdAt;

    private Date updatedAt;

    private Date reviewedAt;

    /** When the approved change was applied to the actual record */
    private Date appliedAt;
}
