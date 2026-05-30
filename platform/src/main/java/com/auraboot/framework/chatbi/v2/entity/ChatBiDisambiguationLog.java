package com.auraboot.framework.chatbi.v2.entity;

import com.auraboot.framework.tenant.typehandler.JsonStringTypeHandler;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

/**
 * Record of a disambiguation prompt emitted by {@code DisambiguationService}
 * along with the user's eventual choice. Two analytic uses:
 *
 * <ul>
 *   <li>Prompt-template tuning — hot ambiguous terms feed back into
 *       {@code chatbi_token_dict} so future questions resolve directly.</li>
 *   <li>UX quality monitoring — Grafana panel raises
 *       {@code chatbi.v2.disambiguation_rate} alert when &gt; 30%/hour
 *       (PRD 17 §12).</li>
 * </ul>
 *
 * <p>Backed by table {@code chatbi_disambiguation_log}. See migration
 * {@code 2026-05-30-chatbi-disambiguation-log.sql} and PRD 17 §5.
 *
 * <p>Auraboot convention: {@code id BIGINT PRIMARY KEY} uses
 * {@link IdType#ASSIGN_ID} (snowflake), not AUTO. JSONB columns must carry
 * {@link JsonStringTypeHandler} and {@link JdbcType#OTHER} or PG rejects the
 * String payload (ENT engineering-gotchas §「数据库 / Schema 治理」).
 */
@Data
@TableName("chatbi_disambiguation_log")
public class ChatBiDisambiguationLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** ULID, 32 chars. */
    private String pid;

    private Long tenantId;

    /** Pid of the answer that emitted this prompt. */
    private String answerPid;

    private String ambiguousTerm;

    /**
     * JSON array of {@code {type, code, label, score}} records. Length 1–3 in
     * the canonical flow but the schema permits more for future N-way prompts.
     */
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = JsonStringTypeHandler.class)
    private String candidatesJson;

    /** Code the user picked; matches one of {@code candidates_json[i].code}. */
    private String userChoice;

    /** {@code LOW_CONFIDENCE} (top1 &lt; 0.5) or {@code AMBIGUOUS} (top1 - top2 &lt; 0.15). */
    private String triggerReason;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    private Instant resolvedAt;
}
