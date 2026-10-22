package com.auraboot.framework.aurabot.skill;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * Discovery payload for {@code GET /api/aurabot/v2/skills}.
 *
 * <p>{@link AuraBotSkillRegistry} builds these from registered beans and the
 * controller serialises them as the SPI contract §10 envelope.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class SkillMeta {
    private String name;
    private String displayName;
    private String category;
    private String riskLevel;
    private JsonNode paramsSchema;
    private Set<String> requiredPermissions;
    private boolean supportsUndo;
    private boolean supportsDryRun;
    private boolean supportsStreaming;
}
