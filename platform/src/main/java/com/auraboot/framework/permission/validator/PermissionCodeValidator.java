package com.auraboot.framework.permission.validator;

import com.auraboot.framework.permission.service.SystemPermissionInitializer;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Pattern;

/**
 * Permission Code Validator
 * 
 * <p>Validates permission code format and provides parsing functionality.
 * 
 * <p>Permission Code Format: {@code {resource_type}.{resource_code}.{action}[.{scope}]}
 *
 * <p>Components:
 * <ul>
 *   <li>resource_type: Lowercase with underscores (e.g., model, page, component)</li>
 *   <li>resource_code: Must start with letter, can contain lowercase letters, numbers, underscores (e.g., model, test2, user_v2)</li>
 *   <li>action: Lowercase with underscores (e.g., manage, read, write, admin)</li>
 *   <li>scope (optional): Lowercase with underscores (e.g., tenant, global, personal)</li>
 * </ul>
 *
 * <p>Valid Examples:
 * <ul>
 *   <li>{@code model.model.manage}</li>
 *   <li>{@code model.test2.manage} - resource_code with number</li>
 *   <li>{@code model.user_v2.manage} - resource_code with letter and number</li>
 *   <li>{@code model.model_123.read} - resource_code with underscore and number</li>
 *   <li>{@code page.publish.read}</li>
 *   <li>{@code component.component.admin}</li>
 *   <li>{@code model.user_model.manage.tenant}</li>
 * </ul>
 *
 * <p>Invalid Examples:
 * <ul>
 *   <li>{@code MODEL.model.manage} - resource_type must be lowercase</li>
 *   <li>{@code model.Model.manage} - resource_code must be lowercase</li>
 *   <li>{@code model.2test.manage} - resource_code cannot start with number</li>
 *   <li>{@code model.model-test.manage} - resource_code cannot contain hyphen</li>
 *   <li>{@code model.model} - missing action</li>
 *   <li>{@code model.model.manage.scope.extra} - too many parts</li>
 * </ul>
 * 
 * @author AuraBoot Platform
 * @version 1.0.0
 * @since 2025-01-08
 */
@Slf4j
public class PermissionCodeValidator {
    
    /**
     * Regex pattern for permission code validation
     * 
     * <p>Pattern breakdown:
     * <ul>
     *   <li>^[a-z_]+ - resource_type (lowercase with underscores)</li>
     *   <li>\\. - dot separator</li>
     *   <li>[a-z][a-z0-9_]* - resource_code (must start with letter, can contain letters, numbers, underscores)</li>
     *   <li>\\. - dot separator</li>
     *   <li>[a-z_]+ - action (lowercase with underscores)</li>
     *   <li>(\\.[a-z_]+)? - optional scope (lowercase with underscores)</li>
     * </ul>
     * 
     * <p>Updated in v2.2.2 to support numbers in resource_code (e.g., test2, user_v2, model_123)
     */
    private static final Pattern CODE_PATTERN = 
        Pattern.compile("^[a-z_]+\\.[a-z][a-z0-9_]*\\.[a-z_]+(\\.[a-z_]+)?$");
    
    /**
     * Valid resource types
     * 
     * <p>Note: This should match the ResourceType enum in the system.
     */

    
    /**
     * Validate permission code format
     * 
     * <p>Validation Rules:
     * <ol>
     *   <li>Code must not be null or empty</li>
     *   <li>Code must match the regex pattern</li>
     *   <li>Code must have 3 or 4 parts (separated by dots)</li>
     *   <li>RESOURCE_TYPE must be a valid enum value</li>
     * </ol>
     * 
     * @param code Permission code to validate
     * @return true if valid, false otherwise
     */
    public static boolean isValid(String code) {
        if (code == null || code.isEmpty()) {
            log.debug("Permission code is null or empty");
            return false;
        }
        
        // Check regex pattern
        if (!CODE_PATTERN.matcher(code).matches()) {
            log.debug("Permission code does not match pattern: code={}", code);
            return false;
        }
        
        // Check number of parts
        String[] parts = code.split("\\.");
        if (parts.length < 3 || parts.length > 4) {
            log.debug("Permission code has invalid number of parts: code={}, parts={}",
                code, parts.length);
            return false;
        }
        
        // Validate resource_type is valid
        String resourceType = parts[0];
        if (!isValidResourceType(resourceType)) {
            log.debug("Invalid resource type: resourceType={}", resourceType);
            return false;
        }
        
        log.trace("Permission code is valid: code={}", code);
        return true;
    }
    
    /**
     * Check if resource type is valid
     * 
     * @param resourceType Resource type to check
     * @return true if valid, false otherwise
     */
    private static boolean isValidResourceType(String resourceType) {
        for (String validType : SystemPermissionInitializer.RESOURCE_TYPES) {
            if (validType.equals(resourceType)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Parse permission code into components
     * 
     * <p>This method validates the code first, then splits it into parts.
     * 
     * @param code Permission code to parse
     * @return PermissionCodeParts object containing parsed components
     * @throws IllegalArgumentException if code is invalid
     */
    public static PermissionCodeParts parse(String code) {
        if (!isValid(code)) {
            throw new IllegalArgumentException("Invalid permission code: " + code);
        }
        
        String[] parts = code.split("\\.");
        
        PermissionCodeParts result = new PermissionCodeParts(
            parts[0],  // resource_type
            parts[1],  // resource_code
            parts[2],  // action
            parts.length > 3 ? parts[3] : null  // scope (optional)
        );
        
        log.debug("Parsed permission code: code={}, parts={}", code, result);
        
        return result;
    }
    
    /**
     * Build permission code from components
     * 
     * <p>This is the reverse operation of {@link #parse(String)}.
     * 
     * @param resourceType Resource type (e.g., "model")
     * @param resourceCode Resource code (e.g., "model")
     * @param action Action (e.g., "manage")
     * @param scope Scope (optional, e.g., "tenant")
     * @return Permission code string
     * @throws IllegalArgumentException if any component is invalid
     */
    public static String build(String resourceType, String resourceCode, 
                               String action, String scope) {
        if (resourceType == null || resourceType.isEmpty()) {
            throw new IllegalArgumentException("Resource type cannot be null or empty");
        }
        if (resourceCode == null || resourceCode.isEmpty()) {
            throw new IllegalArgumentException("Resource code cannot be null or empty");
        }
        if (action == null || action.isEmpty()) {
            throw new IllegalArgumentException("Action cannot be null or empty");
        }
        
        StringBuilder code = new StringBuilder();
        code.append(resourceType)
            .append(".")
            .append(resourceCode)
            .append(".")
            .append(action);
        
        if (scope != null && !scope.isEmpty()) {
            code.append(".").append(scope);
        }
        
        String result = code.toString();
        
        // Validate the built code
        if (!isValid(result)) {
            throw new IllegalArgumentException(
                "Built permission code is invalid: " + result);
        }
        
        log.debug("Built permission code: resourceType={}, resourceCode={}, action={}, scope={}, code={}",
            resourceType, resourceCode, action, scope, result);
        
        return result;
    }
    
    /**
     * Permission Code Parts
     * 
     * <p>Data class to hold parsed permission code components.
     */
    @Data
    public static class PermissionCodeParts {
        private final String resourceType;
        private final String resourceCode;
        private final String action;
        private final String scope;
        
        /**
         * Check if scope is present
         * 
         * @return true if scope is not null and not empty
         */
        public boolean hasScope() {
            return scope != null && !scope.isEmpty();
        }
        
        /**
         * Convert back to permission code string
         * 
         * @return Permission code string
         */
        public String toCode() {
            return build(resourceType, resourceCode, action, scope);
        }
        
        @Override
        public String toString() {
            return String.format("PermissionCodeParts{resourceType='%s', resourceCode='%s', action='%s', scope='%s'}",
                resourceType, resourceCode, action, scope);
        }
    }
}
