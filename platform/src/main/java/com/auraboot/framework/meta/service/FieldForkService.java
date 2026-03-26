package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.FieldForkRequest;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.entity.FieldForkHistory;

import java.util.List;
import java.util.Optional;

/**
 * Field fork service interface
 * Manages field fork operations for creating field variants
 * 
 * @author AuraBoot Team
 * @since 2.2.0
 */
public interface FieldForkService {

    /**
     * Fork a field to create a variant
     * Creates a new field based on the original with modifications
     * 
     * @param originalFieldPid Original field PID
     * @param request Fork request with modifications
     * @return Forked field DTO
     */
    MetaFieldDTO forkField(String originalFieldPid, FieldForkRequest request);

    /**
     * Get fork history for a field
     * Returns all fork operations involving this field
     * 
     * @param fieldPid Field PID
     * @return List of fork history records
     */
    List<FieldForkHistory> getForkHistory(String fieldPid);

    /**
     * Get original field for a forked field
     * Traces back to the original field
     * 
     * @param forkedFieldPid Forked field PID
     * @return Original field DTO
     */
    Optional<MetaFieldDTO> getOriginalField(String forkedFieldPid);

    /**
     * Get all forked variants of a field
     * Returns all fields forked from this original
     * 
     * @param originalFieldPid Original field PID
     * @return List of forked field DTOs
     */
    List<MetaFieldDTO> getForkedVariants(String originalFieldPid);

    /**
     * Replace field in model binding with forked variant
     * Updates the binding to use the forked field instead
     * 
     * @param modelPid Model PID
     * @param originalFieldPid Original field PID
     * @param forkedFieldPid Forked field PID
     */
    void replaceFieldInBinding(String modelPid, String originalFieldPid, String forkedFieldPid);
}
