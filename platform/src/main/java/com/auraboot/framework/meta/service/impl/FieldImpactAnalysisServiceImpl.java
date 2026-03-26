package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.service.FieldImpactAnalysisService;
import com.auraboot.framework.meta.service.FieldUsageService;
import com.auraboot.framework.meta.service.MetaFieldService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Field impact analysis service implementation
 * Analyzes the impact of field modifications on existing models
 * 
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FieldImpactAnalysisServiceImpl implements FieldImpactAnalysisService {

    private final MetaFieldService metaFieldService;
    private final FieldUsageService fieldUsageService;

    @Override
    public ModificationImpact analyzeModificationImpact(String fieldPid, FieldModification modification) {
        log.debug("Analyzing modification impact for field: {}", fieldPid);

        // Get field
        MetaFieldDTO field = metaFieldService.findByPid(fieldPid);
        if (field == null) {
            return ModificationImpact.builder()
                .fieldPid(fieldPid)
                .modificationType(ModificationType.SAFE)
                .affectedModels(Collections.emptyList())
                .totalAffectedModels(0)
                .impactDescription("Field not found")
                .recommendations(Collections.emptyList())
                .canProceed(false)
                .build();
        }

        // Classify modification
        ModificationType type = classifyModification(modification);

        // Get affected models
        List<AffectedModel> affectedModels = getAffectedModels(fieldPid, modification);

        // Build recommendations
        List<String> recommendations = buildRecommendations(type, affectedModels.size());

        return ModificationImpact.builder()
            .fieldPid(fieldPid)
            .modificationType(type)
            .affectedModels(affectedModels)
            .totalAffectedModels(affectedModels.size())
            .impactDescription(buildImpactDescription(type, affectedModels.size()))
            .recommendations(recommendations)
            .canProceed(type != ModificationType.BREAKING || affectedModels.isEmpty())
            .build();
    }

    @Override
    public boolean isBreakingChange(FieldModification modification) {
        // Check for breaking changes
        if (modification.getNewDataType() != null) {
            return true; // Data type change is breaking
        }
        if (modification.getNewSemanticType() != null) {
            return true; // Semantic type change is breaking
        }
        if (modification.getNewRefTarget() != null) {
            return true; // Reference target change is breaking
        }
        return false;
    }

    @Override
    public List<AffectedModel> getAffectedModels(String fieldPid, FieldModification modification) {
        log.debug("Getting affected models for field: {}", fieldPid);

        // Get models using this field
        List<FieldUsageService.ModelReference> modelRefs = fieldUsageService.getModelsUsingField(fieldPid);

        // Convert to AffectedModel with impact analysis
        List<AffectedModel> affectedModels = new ArrayList<>();
        ModificationType modType = classifyModification(modification);

        for (FieldUsageService.ModelReference ref : modelRefs) {
            String impactLevel = determineImpactLevel(modType);
            List<String> potentialIssues = identifyPotentialIssues(modType);

            AffectedModel affected = AffectedModel.builder()
                .modelPid(ref.getModelPid())
                .modelCode(ref.getModelCode())
                .modelDisplayName(ref.getModelDisplayName())
                .potentialIssues(potentialIssues)
                .impactLevel(impactLevel)
                .build();

            affectedModels.add(affected);
        }

        return affectedModels;
    }

    @Override
    public ModificationType classifyModification(FieldModification modification) {
        if (isBreakingChange(modification)) {
            return ModificationType.BREAKING;
        }

        // Check for warning-level changes
        if (modification.getNewFeature() != null ||
            modification.getNewRuleSchema() != null) {
            return ModificationType.WARNING;
        }

        // Safe changes (description, UI schema, etc.)
        return ModificationType.SAFE;
    }

    @Override
    public ValidationResult validateModificationSafety(String fieldPid, FieldModification modification) {
        log.debug("Validating modification safety for field: {}", fieldPid);

        ValidationResult result = new ValidationResult();

        // Check if field exists
        MetaFieldDTO field = metaFieldService.findByPid(fieldPid);
        if (field == null) {
            result.getErrors().add("Field not found: " + fieldPid);
            result.setCanProceed(false);
            return result;
        }

        // Classify modification
        ModificationType type = classifyModification(modification);

        // Check for breaking changes
        if (type == ModificationType.BREAKING) {
            List<AffectedModel> affected = getAffectedModels(fieldPid, modification);
            if (!affected.isEmpty()) {
                result.getErrors().add("Breaking change affects " + affected.size() + " models");
                result.getSuggestions().add("Consider forking the field instead");
                result.setCanProceed(false);
                return result;
            }
        }

        // Check for warnings
        if (type == ModificationType.WARNING) {
            result.getWarnings().add("This modification may affect existing functionality");
            result.getSuggestions().add("Review affected models before proceeding");
        }

        result.setCanProceed(true);
        return result;
    }

    // ==================== Private Helper Methods ====================

    private String determineImpactLevel(ModificationType type) {
        return switch (type) {
            case BREAKING -> "high";
            case WARNING -> "medium";
            case SAFE -> "low";
        };
    }

    private List<String> identifyPotentialIssues(ModificationType type) {
        List<String> issues = new ArrayList<>();
        if (type == ModificationType.BREAKING) {
            issues.add("Data type incompatibility");
            issues.add("Existing data may become invalid");
            issues.add("UI components may break");
        } else if (type == ModificationType.WARNING) {
            issues.add("Validation rules may change");
            issues.add("UI behavior may change");
        }
        return issues;
    }

    private String buildImpactDescription(ModificationType type, int affectedCount) {
        if (affectedCount == 0) {
            return "No models are affected by this modification";
        }
        return switch (type) {
            case BREAKING -> String.format("Breaking change affects %d models. Fork recommended.", affectedCount);
            case WARNING -> String.format("Warning: %d models may be affected. Review recommended.", affectedCount);
            case SAFE -> String.format("Safe modification. %d models use this field.", affectedCount);
        };
    }

    private List<String> buildRecommendations(ModificationType type, int affectedCount) {
        List<String> recommendations = new ArrayList<>();
        if (type == ModificationType.BREAKING && affectedCount > 0) {
            recommendations.add("Fork the field to create a variant");
            recommendations.add("Update affected models to use the forked field");
            recommendations.add("Test thoroughly before deploying");
        } else if (type == ModificationType.WARNING) {
            recommendations.add("Review affected models");
            recommendations.add("Test in development environment");
        } else {
            recommendations.add("Safe to proceed");
        }
        return recommendations;
    }
}
