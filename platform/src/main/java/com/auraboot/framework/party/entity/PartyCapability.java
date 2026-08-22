package com.auraboot.framework.party.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("ab_party_capability")
public class PartyCapability {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String pid;
    private Long tenantId;
    private Long partyId;
    private String capabilityCode;
    private String status;
    private Instant effectiveAt;
    private Instant expiresAt;
    private Long grantedBy;
    private Long revokedBy;
    private Instant revokedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
