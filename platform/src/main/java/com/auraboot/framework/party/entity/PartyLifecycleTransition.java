package com.auraboot.framework.party.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("ab_party_lifecycle_transition")
public class PartyLifecycleTransition {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String pid;
    private Long tenantId;
    private Long partyId;
    private String fromStatus;
    private String toStatus;
    private String reasonCode;
    private String reason;
    private Long requestedBy;
    private Long approvedBy;
    private Instant occurredAt;
}
