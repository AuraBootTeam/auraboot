package com.auraboot.framework.governance.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.auraboot.framework.application.database.mybatis.JsonbListTypeHandler;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.util.Date;
import java.util.List;

/**
 * Governance policy entity - defines governance rules per model.
 * Controls whether changes require approval and/or auto-snapshot.
 */
@Data
@TableName(value = "ns_governance_policy", autoResultMap = true)
public class MasterDataPolicy {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String pid;

    private Long tenantId;

    /** The model code this policy applies to */
    private String modelCode;

    /** If true, edits on this model go through change request workflow */
    private Boolean requireApproval;

    /** If true, every edit on this model creates a version snapshot */
    private Boolean autoSnapshot;

    /** Optional linked approval chain ID */
    private Long approvalChainId;

    /** JSONB array of role codes that can propose changes */
    @TableField(value = "allowed_editors", jdbcType = JdbcType.OTHER, typeHandler = JsonbListTypeHandler.class)
    private List<String> allowedEditors;

    private Date createdAt;

    private Date updatedAt;
}
