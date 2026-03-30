package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ModelDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PageSchemaDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Computes a quality scorecard for a plugin after validation.
 * <p>
 * 5 dimensions (total 100):
 * - Completeness (25%): page coverage for ENTITY models
 * - Semantic Richness (25%): agent_hint + description quality on commands
 * - Agent Readiness (25%): input_schema + risk_level + output_description
 * - Safety (15%): risk_level on DELETE, side_effect_description on L2+
 * - i18n (10%): displayName presence on models/commands
 */
@Component
public class PluginQualityScorer {

    /**
     * Compute quality score from manifest and validation messages.
     *
     * @return map with dimension scores and overall score
     */
    public Map<String, Object> computeScore(PluginManifestExtended manifest, PluginValidationResult validationResult) {
        Map<String, Object> score = new LinkedHashMap<>();

        int completeness = scoreCompleteness(manifest);
        int semanticRichness = scoreSemanticRichness(manifest);
        int agentReadiness = scoreAgentReadiness(manifest);
        int safety = scoreSafety(manifest);
        int i18n = scoreI18n(manifest);

        score.put("completeness", completeness);
        score.put("semanticRichness", semanticRichness);
        score.put("agentReadiness", agentReadiness);
        score.put("safety", safety);
        score.put("i18n", i18n);

        // Weighted overall: 25 + 25 + 25 + 15 + 10 = 100
        int overall = (completeness * 25 + semanticRichness * 25 + agentReadiness * 25
                + safety * 15 + i18n * 10) / 100;
        score.put("overall", overall);

        // Validation message counts
        score.put("errors", validationResult.getErrorCount());
        score.put("warnings", validationResult.getWarningCount());
        score.put("infos", validationResult.getInfoCount());

        return score;
    }

    /**
     * Page coverage: every ENTITY model should have List + Form/Detail pages.
     * Score = (models with both pages / total ENTITY models) * 100
     */
    private int scoreCompleteness(PluginManifestExtended manifest) {
        List<ModelDefinitionDTO> models = manifest.getModels();
        List<PageSchemaDTO> pages = manifest.getPages();
        if (models == null || models.isEmpty()) return 100;

        Set<String> entityModels = new HashSet<>();
        for (ModelDefinitionDTO m : models) {
            if ("entity".equalsIgnoreCase(m.getModelType())) {
                entityModels.add(m.getCode());
            }
        }
        if (entityModels.isEmpty()) return 100;

        Set<String> hasListPage = new HashSet<>();
        Set<String> hasFormPage = new HashSet<>();
        if (pages != null) {
            for (PageSchemaDTO page : pages) {
                String mc = page.getModelCode();
                if (mc == null) continue;
                String pk = page.getKind();
                if ("list".equals(pk)) hasListPage.add(mc);
                else if ("form".equals(pk) || "detail".equals(pk)) hasFormPage.add(mc);
            }
        }

        int covered = 0;
        for (String code : entityModels) {
            if (hasListPage.contains(code) && hasFormPage.contains(code)) covered++;
        }
        return entityModels.size() > 0 ? (covered * 100 / entityModels.size()) : 100;
    }

    /**
     * Semantic richness: commands with agent_hint or meaningful description (>= 30 chars).
     * Score = (rich commands / total commands) * 100
     */
    private int scoreSemanticRichness(PluginManifestExtended manifest) {
        List<CommandDefinitionDTO> commands = manifest.getCommands();
        if (commands == null || commands.isEmpty()) return 100;

        int rich = 0;
        for (CommandDefinitionDTO cmd : commands) {
            boolean hasHint = hasNonBlankUnknown(cmd, "agentHint")
                    || hasNonBlankUnknown(cmd, "agent_hint");
            String desc = cmd.getDescription();
            boolean hasDesc = desc != null && desc.length() >= 30;
            if (hasHint || hasDesc) rich++;
        }
        return rich * 100 / commands.size();
    }

    /**
     * Agent readiness: commands with input_schema/inputFields + risk_level.
     * Score = average of (hasInputSpec rate + hasRiskLevel rate) * 100
     */
    private int scoreAgentReadiness(PluginManifestExtended manifest) {
        List<CommandDefinitionDTO> commands = manifest.getCommands();
        if (commands == null || commands.isEmpty()) return 100;

        int withInput = 0;
        int withRisk = 0;
        int writeCommands = 0;

        for (CommandDefinitionDTO cmd : commands) {
            Map<String, Object> exec = cmd.getConsolidatedExecutionConfig();
            String type = (String) exec.get("type");

            // Input spec check (skip QUERY/DELETE which don't need input)
            if (!"delete".equals(type) && !"query".equals(type)) {
                Object inputSchema = cmd.getInputSchema();
                boolean hasInputFields = exec.get("inputFields") instanceof List<?> list && !list.isEmpty();
                if (inputSchema != null || hasInputFields) withInput++;
            } else {
                withInput++; // QUERY/DELETE get free pass
            }

            // Risk level check (only for write commands)
            if (!"query".equals(type)) {
                writeCommands++;
                boolean hasRisk = hasNonBlankUnknown(cmd, "cmd_risk_level")
                        || exec.get("riskLevel") != null;
                if (hasRisk) withRisk++;
            }
        }

        int inputRate = commands.size() > 0 ? (withInput * 100 / commands.size()) : 100;
        int riskRate = writeCommands > 0 ? (withRisk * 100 / writeCommands) : 100;
        return (inputRate + riskRate) / 2;
    }

    /**
     * Safety: DELETE commands with risk_level, L2+ commands with side_effect_description.
     * Score = (safe commands / applicable commands) * 100
     */
    @SuppressWarnings("unchecked")
    private int scoreSafety(PluginManifestExtended manifest) {
        List<CommandDefinitionDTO> commands = manifest.getCommands();
        if (commands == null || commands.isEmpty()) return 100;

        int applicable = 0;
        int compliant = 0;

        for (CommandDefinitionDTO cmd : commands) {
            Map<String, Object> exec = cmd.getConsolidatedExecutionConfig();
            String type = (String) exec.get("type");

            // DELETE must have risk_level
            if ("delete".equals(type) || "bulk_delete".equals(type)) {
                applicable++;
                if (hasNonBlankUnknown(cmd, "cmd_risk_level") || exec.get("riskLevel") != null) {
                    compliant++;
                }
            }

            // Commands with sideEffects must have description
            Object sideEffects = exec.get("sideEffects");
            if (sideEffects instanceof List<?> list && !list.isEmpty()) {
                applicable++;
                boolean hasSideDesc = hasNonBlankUnknown(cmd, "sideEffectDescription")
                        || hasNonBlankUnknown(cmd, "side_effect_description")
                        || (cmd.getDescription() != null && cmd.getDescription().length() > 20);
                if (hasSideDesc) compliant++;
            }
        }

        return applicable > 0 ? (compliant * 100 / applicable) : 100;
    }

    /**
     * i18n: models and commands with displayName.
     * Score = (items with displayName / total items) * 100
     */
    private int scoreI18n(PluginManifestExtended manifest) {
        int total = 0;
        int withName = 0;

        List<ModelDefinitionDTO> models = manifest.getModels();
        if (models != null) {
            for (ModelDefinitionDTO m : models) {
                total++;
                if (m.getDisplayName() != null && !m.getDisplayName().isBlank()) withName++;
            }
        }

        List<CommandDefinitionDTO> commands = manifest.getCommands();
        if (commands != null) {
            for (CommandDefinitionDTO cmd : commands) {
                total++;
                if (cmd.getDisplayName() != null && !cmd.getDisplayName().isBlank()) withName++;
            }
        }

        return total > 0 ? (withName * 100 / total) : 100;
    }

    private boolean hasNonBlankUnknown(CommandDefinitionDTO cmd, String fieldName) {
        Map<String, Object> unknown = cmd.getUnknownFields();
        if (unknown == null) return false;
        Object val = unknown.get(fieldName);
        return val instanceof String s && !s.isBlank();
    }
}
