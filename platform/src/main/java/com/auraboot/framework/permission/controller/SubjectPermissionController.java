package com.auraboot.framework.permission.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.dto.SubjectPermissionCreateRequest;
import com.auraboot.framework.permission.dto.SubjectPermissionDTO;
import com.auraboot.framework.permission.service.SubjectPermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * SubjectPermission Controller
 * 
 * <p>REST API for Subject-Permission declaration management.
 * 
 * <p>Subject Types:
 * <ul>
 *   <li>MENU - Menu items</li>
 *   <li>PAGE - Pages</li>
 *   <li>BUTTON - Buttons</li>
 *   <li>QUERY - Queries</li>
 *   <li>WORKFLOW - Workflows</li>
 * </ul>
 * 
 * <p>Endpoints:
 * <ul>
 *   <li>POST /api/subject-permissions - Add permission declaration</li>
 *   <li>POST /api/subject-permissions/batch - Batch add declarations</li>
 *   <li>DELETE /api/subject-permissions/{id} - Remove declaration</li>
 *   <li>DELETE /api/subject-permissions/subject - Remove all declarations for subject</li>
 *   <li>GET /api/subject-permissions/subject - List declarations by subject</li>
 *   <li>GET /api/subject-permissions/subject-code - List declarations by subject code</li>
 *   <li>GET /api/subject-permissions/evaluate - Evaluate visibility</li>
 *   <li>POST /api/subject-permissions/evaluate/batch - Batch evaluate visibility</li>
 *   <li>GET /api/subject-permissions/validate-logic-group - Validate logic group consistency</li>
 * </ul>
 * 
 * @author AuraBoot Platform
 * @since V4
 */
@Slf4j
@RestController
@RequestMapping("/api/subject-permissions")
@RequiredArgsConstructor
@Validated
public class SubjectPermissionController {
    
    private final SubjectPermissionService subjectPermissionService;
    
    /**
     * Add permission declaration to subject
     * 
     * @param request Create request
     * @return Created declaration
     */
    @PostMapping
    public ApiResponse<SubjectPermissionDTO> addPermission(
            @Valid @RequestBody SubjectPermissionCreateRequest request) {
        
        log.info("Adding permission declaration: subjectType={}, subjectId={}, permissionId={}",
                request.getSubjectType(), request.getSubjectId(), request.getPermissionId());
        
        SubjectPermissionDTO declaration = subjectPermissionService.addPermission(request);
        
        log.info("Permission declaration added: id={}", declaration.getId());
        
        return ApiResponse.success(declaration);
    }
    
    /**
     * Batch add permission declarations
     * 
     * @param subjectType Subject type
     * @param subjectId Subject ID
     * @param requests List of create requests
     * @return List of created declarations
     */
    @PostMapping("/batch")
    public ApiResponse<List<SubjectPermissionDTO>> batchAddPermissions(
            @RequestParam @NotNull String subjectType,
            @RequestParam @NotNull Long subjectId,
            @Valid @RequestBody List<SubjectPermissionCreateRequest> requests) {
        
        log.info("Batch adding permission declarations: subjectType={}, subjectId={}, count={}",
                subjectType, subjectId, requests.size());
        
        List<SubjectPermissionDTO> declarations = 
                subjectPermissionService.batchAddPermissions(subjectType, subjectId, requests);
        
        log.info("Batch permission declarations added: count={}", declarations.size());
        
        return ApiResponse.success(declarations);
    }
    
    /**
     * Remove permission declaration
     * 
     * @param id Declaration ID
     * @return Success response
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> removePermission(@PathVariable @NotNull Long id) {
        log.info("Removing permission declaration: id={}", id);
        
        subjectPermissionService.removePermission(id);
        
        log.info("Permission declaration removed: id={}", id);
        
        return ApiResponse.success();
    }
    
    /**
     * Remove all declarations for a subject
     * 
     * @param subjectType Subject type
     * @param subjectId Subject ID
     * @return Success response
     */
    @DeleteMapping("/subject")
    public ApiResponse<Void> removeAllPermissions(
            @RequestParam @NotNull String subjectType,
            @RequestParam @NotNull Long subjectId) {
        
        log.info("Removing all permission declarations: subjectType={}, subjectId={}",
                subjectType, subjectId);
        
        subjectPermissionService.removeAllPermissions(subjectType, subjectId);
        
        log.info("All permission declarations removed: subjectType={}, subjectId={}",
                subjectType, subjectId);
        
        return ApiResponse.success();
    }
    
    /**
     * List declarations by subject
     * 
     * @param subjectType Subject type
     * @param subjectId Subject ID
     * @return List of declarations
     */
    @GetMapping("/subject")
    public ApiResponse<List<SubjectPermissionDTO>> listBySubject(
            @RequestParam @NotNull String subjectType,
            @RequestParam @NotNull Long subjectId) {
        
        log.debug("Listing permission declarations: subjectType={}, subjectId={}",
                subjectType, subjectId);
        
        List<SubjectPermissionDTO> declarations = 
                subjectPermissionService.findBySubject(subjectType, subjectId);
        
        return ApiResponse.success(declarations);
    }
    
    /**
     * List declarations by subject code
     * 
     * @param subjectType Subject type
     * @param subjectCode Subject code
     * @return List of declarations
     */
    @GetMapping("/subject-code")
    public ApiResponse<List<SubjectPermissionDTO>> listBySubjectCode(
            @RequestParam @NotNull String subjectType,
            @RequestParam @NotNull String subjectCode) {
        
        log.debug("Listing permission declarations: subjectType={}, subjectCode={}",
                subjectType, subjectCode);
        
        List<SubjectPermissionDTO> declarations = 
                subjectPermissionService.findBySubjectCode(subjectType, subjectCode);
        
        return ApiResponse.success(declarations);
    }
    
    /**
     * Evaluate subject visibility for user
     * 
     * <p>Note: This is for UI visibility only, not for backend authorization.
     * 
     * @param subjectType Subject type
     * @param subjectId Subject ID
     * @param userId User ID
     * @return Visibility result
     */
    @GetMapping("/evaluate")
    public ApiResponse<Boolean> evaluateVisibility(
            @RequestParam @NotNull String subjectType,
            @RequestParam @NotNull Long subjectId,
            @RequestParam @NotNull Long userId) {
        
        log.debug("Evaluating subject visibility: subjectType={}, subjectId={}, userId={}",
                subjectType, subjectId, userId);
        
        boolean visible = subjectPermissionService.evaluateVisibility(subjectType, subjectId, userId);
        
        return ApiResponse.success(visible);
    }
    
    /**
     * Batch evaluate visibility for multiple subjects
     * 
     * @param subjectType Subject type
     * @param subjectIds List of subject IDs
     * @param userId User ID
     * @return Map of subject ID to visibility result
     */
    @PostMapping("/evaluate/batch")
    public ApiResponse<Map<Long, Boolean>> batchEvaluateVisibility(
            @RequestParam @NotNull String subjectType,
            @RequestBody @NotNull List<Long> subjectIds,
            @RequestParam @NotNull Long userId) {
        
        log.debug("Batch evaluating subject visibility: subjectType={}, count={}, userId={}",
                subjectType, subjectIds.size(), userId);
        
        Map<Long, Boolean> results = 
                subjectPermissionService.batchEvaluateVisibility(subjectType, subjectIds, userId);
        
        return ApiResponse.success(results);
    }
    
    /**
     * Validate logic group consistency
     * 
     * <p>Checks if all declarations in the same logic group have the same group_logic_type.
     * 
     * @param subjectType Subject type
     * @param subjectId Subject ID
     * @param logicGroup Logic group number
     * @return Validation result
     */
    @GetMapping("/validate-logic-group")
    public ApiResponse<Boolean> validateLogicGroupConsistency(
            @RequestParam @NotNull String subjectType,
            @RequestParam @NotNull Long subjectId,
            @RequestParam @NotNull Integer logicGroup) {
        
        log.debug("Validating logic group consistency: subjectType={}, subjectId={}, logicGroup={}",
                subjectType, subjectId, logicGroup);
        
        boolean consistent = subjectPermissionService.validateLogicGroupConsistency(
                subjectType, subjectId, logicGroup);
        
        return ApiResponse.success(consistent);
    }
}
