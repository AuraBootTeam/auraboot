package com.auraboot.framework.party.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("ab_actor_preference")
public class ActorPreference {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String pid;
    private Long tenantId;
    private Long tenantMemberId;
    private Long preferredPartyId;
    private Long lastPartyId;
    private String actorSelectionMode;
    private Long contextVersion;
    private Instant createdAt;
    private Instant updatedAt;
}
