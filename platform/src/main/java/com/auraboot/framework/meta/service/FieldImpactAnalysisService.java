package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.AffectedModel;
import com.auraboot.framework.meta.dto.FieldModification;
import com.auraboot.framework.meta.dto.ModificationImpact;
import com.auraboot.framework.meta.dto.ModificationType;

import java.util.List;

/**
 * Field impact analysis service interface
 * Analyzes the impact of field modifications on existing models
 * 
 * @author AuraBoot Team
 * @since 2.2.0
 */
public interface FieldImpactAnalysisService {

    /**
     * Analyze modification impact
     * Determines the impact of proposed field changes
     * 
     * @param fieldPid Field PID
     * @param modification Proposed modification
     * @return Impact analysis result
     */
    ModificationImpact analyzeModificationImpact(String fieldPid, FieldModification modification);

    /**
     * Check if modification is breaking change
     * Breaking changes include: base_type, semantic_type, validation rules removal, ref_target changes
     * 
     * @param modification Proposed modification
     * @return true if breaking change
     */
    boolean isBreakingChange(FieldModification modification);

    /**
     * Get affected models for modification
     * Returns all models that use this field
     * 
     * @param fieldPid Field PID
     * @param modification Proposed modification
     * @return List of affected models with impact levels
     */
    List<AffectedModel> getAffectedModels(String fieldPid, FieldModification modification);

    /**
     * Classify modification type
     * 
     * @param modification Proposed modification
     * @return BREAKING, WARNING, or SAFE
     */
    ModificationType classifyModification(FieldModification modification);

    /**
     * Validate modification safety
     * 
     * @param fieldPid Field PID
     * @param modification Proposed modification
     * @return Validation result with suggestions
     */
    ValidationResult validateModificationSafety(String fieldPid, FieldModification modification);

    /**
     * Validation result DTO
     */
    class ValidationResult {
        private boolean canProceed;
        private List<String> errors;
        private List<String> warnings;
        private List<String> suggestions;

        public ValidationResult() {
            this.errors = new java.util.ArrayList<>();
            this.warnings = new java.util.ArrayList<>();
            this.suggestions = new java.util.ArrayList<>();
        }

        // Getters and setters
        public boolean isCanProceed() { return canProceed; }
        public void setCanProceed(boolean canProceed) { this.canProceed = canProceed; }
        public List<String> getErrors() { return errors; }
        public void setErrors(List<String> errors) { this.errors = errors; }
        public List<String> getWarnings() { return warnings; }
        public void setWarnings(List<String> warnings) { this.warnings = warnings; }
        public List<String> getSuggestions() { return suggestions; }
        public void setSuggestions(List<String> suggestions) { this.suggestions = suggestions; }
    }
}
