package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.BindingConfiguration;

import java.util.List;

/**
 * Field usage service interface
 * Tracks and reports field usage across models, pages, and queries
 * 
 * @author AuraBoot Team
 * @since 2.2.0
 */
public interface FieldUsageService {

    /**
     * Get field usage information
     * Returns cached usage statistics
     * 
     * @param fieldPid Field PID
     * @return Usage information
     */
    FieldUsageInfo getFieldUsage(String fieldPid);

    /**
     * Check if field is used by any model
     * 
     * @param fieldPid Field PID
     * @return true if field is used
     */
    boolean isFieldUsed(String fieldPid);

    /**
     * Get models using this field
     * 
     * @param fieldPid Field PID
     * @return List of model references
     */
    List<ModelReference> getModelsUsingField(String fieldPid);

    /**
     * Get binding configurations for each model
     * 
     * @param fieldPid Field PID
     * @return List of binding configurations
     */
    List<BindingConfiguration> getBindingConfigurations(String fieldPid);

    /**
     * Export field usage report
     * 
     * @param fieldPid Field PID
     * @return Usage report
     */
    FieldUsageReport exportUsageReport(String fieldPid);

    /**
     * Calculate and update field usage statistics
     * Updates the usage cache table
     * 
     * @param fieldPid Field PID
     * @return Updated usage statistics
     */
    FieldUsageStatistics calculateUsageStatistics(String fieldPid);

    /**
     * Refresh usage cache for a field
     * 
     * @param fieldPid Field PID
     */
    void refreshUsageCache(String fieldPid);

    /**
     * Refresh usage cache for all fields
     * Scheduled task to keep cache up-to-date
     */
    void refreshAllUsageCache();

    /**
     * Field usage information DTO
     */
    class FieldUsageInfo {
        private String fieldPid;
        private String fieldCode;
        private int modelCount;
        private int pageCount;
        private int queryCount;
        private int totalReferences;
        private boolean isCoreField;
        private java.time.Instant lastUsedAt;
        private java.math.BigDecimal usageFrequency;

        // Getters and setters
        public String getFieldPid() { return fieldPid; }
        public void setFieldPid(String fieldPid) { this.fieldPid = fieldPid; }
        public String getFieldCode() { return fieldCode; }
        public void setFieldCode(String fieldCode) { this.fieldCode = fieldCode; }
        public int getModelCount() { return modelCount; }
        public void setModelCount(int modelCount) { this.modelCount = modelCount; }
        public int getPageCount() { return pageCount; }
        public void setPageCount(int pageCount) { this.pageCount = pageCount; }
        public int getQueryCount() { return queryCount; }
        public void setQueryCount(int queryCount) { this.queryCount = queryCount; }
        public int getTotalReferences() { return totalReferences; }
        public void setTotalReferences(int totalReferences) { this.totalReferences = totalReferences; }
        public boolean isCoreField() { return isCoreField; }
        public void setCoreField(boolean coreField) { isCoreField = coreField; }
        public java.time.Instant getLastUsedAt() { return lastUsedAt; }
        public void setLastUsedAt(java.time.Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
        public java.math.BigDecimal getUsageFrequency() { return usageFrequency; }
        public void setUsageFrequency(java.math.BigDecimal usageFrequency) { this.usageFrequency = usageFrequency; }
    }

    /**
     * Model reference DTO
     */
    class ModelReference {
        private String modelPid;
        private String modelCode;
        private String modelDisplayName;

        // Getters and setters
        public String getModelPid() { return modelPid; }
        public void setModelPid(String modelPid) { this.modelPid = modelPid; }
        public String getModelCode() { return modelCode; }
        public void setModelCode(String modelCode) { this.modelCode = modelCode; }
        public String getModelDisplayName() { return modelDisplayName; }
        public void setModelDisplayName(String modelDisplayName) { this.modelDisplayName = modelDisplayName; }
    }

    /**
     * Field usage report DTO
     */
    class FieldUsageReport {
        private String fieldPid;
        private String fieldCode;
        private FieldUsageInfo usageInfo;
        private List<ModelReference> models;
        private List<String> pages;
        private List<String> queries;
        private java.time.Instant generatedAt;

        // Getters and setters
        public String getFieldPid() { return fieldPid; }
        public void setFieldPid(String fieldPid) { this.fieldPid = fieldPid; }
        public String getFieldCode() { return fieldCode; }
        public void setFieldCode(String fieldCode) { this.fieldCode = fieldCode; }
        public FieldUsageInfo getUsageInfo() { return usageInfo; }
        public void setUsageInfo(FieldUsageInfo usageInfo) { this.usageInfo = usageInfo; }
        public List<ModelReference> getModels() { return models; }
        public void setModels(List<ModelReference> models) { this.models = models; }
        public List<String> getPages() { return pages; }
        public void setPages(List<String> pages) { this.pages = pages; }
        public List<String> getQueries() { return queries; }
        public void setQueries(List<String> queries) { this.queries = queries; }
        public java.time.Instant getGeneratedAt() { return generatedAt; }
        public void setGeneratedAt(java.time.Instant generatedAt) { this.generatedAt = generatedAt; }
    }

    /**
     * Field usage statistics DTO
     */
    class FieldUsageStatistics {
        private String fieldPid;
        private int modelCount;
        private int pageCount;
        private int queryCount;
        private int totalReferences;
        private java.math.BigDecimal usageFrequency;
        private java.time.Instant calculatedAt;

        // Getters and setters
        public String getFieldPid() { return fieldPid; }
        public void setFieldPid(String fieldPid) { this.fieldPid = fieldPid; }
        public int getModelCount() { return modelCount; }
        public void setModelCount(int modelCount) { this.modelCount = modelCount; }
        public int getPageCount() { return pageCount; }
        public void setPageCount(int pageCount) { this.pageCount = pageCount; }
        public int getQueryCount() { return queryCount; }
        public void setQueryCount(int queryCount) { this.queryCount = queryCount; }
        public int getTotalReferences() { return totalReferences; }
        public void setTotalReferences(int totalReferences) { this.totalReferences = totalReferences; }
        public java.math.BigDecimal getUsageFrequency() { return usageFrequency; }
        public void setUsageFrequency(java.math.BigDecimal usageFrequency) { this.usageFrequency = usageFrequency; }
        public java.time.Instant getCalculatedAt() { return calculatedAt; }
        public void setCalculatedAt(java.time.Instant calculatedAt) { this.calculatedAt = calculatedAt; }
    }
}
