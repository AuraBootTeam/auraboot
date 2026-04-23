package com.auraboot.framework.meta.template.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for CRUD template generation
 * 
 * @author AuraBoot
 */
@Data
public class CrudTemplateConfig {
    
    /**
     * Menu name
     */
    private String menuName;
    
    /**
     * Parent menu ID (optional)
     */
    private Long menuParentId;
    
    /**
     * Menu icon
     */
    private String menuIcon = "DocumentTextIcon";
    
    /**
     * Default roles to assign permissions to
     */
    private List<String> defaultRoles = new ArrayList<>();

    /**
     * Whether to create a menu entry
     */
    private boolean createMenu = false;

    /**
     * Whether to create permissions
     */
    private boolean createPermissions = false;

    /**
     * Whether to assign permissions to roles
     */
    private boolean assignRoles = false;
    
    /**
     * Whether to generate list page
     */
    private boolean generateList = true;
    
    /**
     * Whether to generate form page
     */
    private boolean generateForm = true;
    
    /**
     * Whether to generate detail page
     */
    private boolean generateDetail = true;
    
    /**
     * Whether to enable export functionality
     */
    private boolean enableExport = true;
    
    /**
     * Whether to enable import functionality
     */
    private boolean enableImport = false;
    
    /**
     * List page columns to display (optional, empty means all fields)
     */
    private List<String> listColumns = new ArrayList<>();
    
    /**
     * Form page fields to display (optional, empty means all fields)
     */
    private List<String> formFields = new ArrayList<>();
    
    /**
     * Detail page fields to display (optional, empty means all fields)
     */
    private List<String> detailFields = new ArrayList<>();
}
