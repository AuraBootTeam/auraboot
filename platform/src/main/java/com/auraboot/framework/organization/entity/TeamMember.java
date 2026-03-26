package com.auraboot.framework.organization.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("ab_team_member")
public class TeamMember {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String pid;

    private Long tenantId;

    private Long teamId;

    private Long userId;

    private String role; // LEADER, MEMBER

    private Instant joinedAt;

    private Instant createdAt;

    private Instant updatedAt;

    private Long createdBy;

    private Long updatedBy;
}
