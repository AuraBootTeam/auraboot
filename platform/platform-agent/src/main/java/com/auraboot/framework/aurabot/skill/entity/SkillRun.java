package com.auraboot.framework.aurabot.skill.entity;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Persistence row for {@code ab_aurabot_skill_run}.
 *
 * <p>Field naming follows the DB column mapping documented in
 * {@code SkillRunMapper}; do NOT add Spring/MyBatis-Plus annotations here —
 * the mapper uses MyBatis {@code @Results} explicitly to keep the
 * idempotency-anchor table decoupled from MyBatis-Plus auto-fill.
 *
 * <p>{@code status} and {@code riskLevel} hold the {@code .code()} string of
 * the corresponding enum (lowercase). Conversion is handled in
 * {@link com.auraboot.framework.aurabot.skill.SkillRunRepository} —
 * direct {@code enum.name()} storage is a project red-line (AGENTS.md).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class SkillRun {

    private String pid;
    private Long tenantId;
    private String skillName;
    private JsonNode paramsJson;
    private JsonNode beforeSnapshot;
    private JsonNode afterSnapshot;
    private String idempotencyKey;
    private String undoToken;
    private String batchId;
    /** {@link com.auraboot.framework.aurabot.skill.SkillRunStatus#code()}. */
    private String status;
    /** {@link com.auraboot.framework.aurabot.skill.RiskLevel#code()}. */
    private String riskLevel;
    private String createdBy;
    private Instant createdAt;
    private Instant undoneAt;
    private Boolean deletedFlag;
}
