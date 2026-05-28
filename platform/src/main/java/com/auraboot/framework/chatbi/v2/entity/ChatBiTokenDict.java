package com.auraboot.framework.chatbi.v2.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * Operator-maintained synonym dictionary. PRD 17 §5 table 4.
 *
 * <p>Resolves natural-language terms (e.g. "销售额", "GMV") to canonical
 * METRIC / DIMENSION / VALUE / KEYWORD codes. {@code tenant_id} nullable
 * means a global default; tenant-specific entries override globals via
 * {@code priority} DESC.
 */
@Data
@TableName("chatbi_token_dict")
public class ChatBiTokenDict {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Nullable — null = global default applying to all tenants. */
    private Long tenantId;

    private String term;

    /** {@code METRIC / DIMENSION / VALUE / KEYWORD}. */
    private String resolvesToType;

    private String resolvesToCode;

    private Integer priority;

    /** {@code manual / mined / llm_suggested}. */
    private String source;

    private Long approvedByUserId;
}
