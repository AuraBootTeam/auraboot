package com.auraboot.framework.meta.template.dto;

import jakarta.validation.constraints.NotBlank;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    private boolean createMenu = true;

    /**
     * Whether to create permissions
     */
    private boolean createPermissions = true;

    /**
     * Whether to assign permissions to roles
     */
    private boolean assignRoles = true;
    
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

    private FormPresentation formPresentation;

    @Data
    public static class FormPresentation {
        public enum Width { @JsonProperty("comfortable") COMFORTABLE, @JsonProperty("wide") WIDE, @JsonProperty("full") FULL }
        public enum Surface { @JsonProperty("card") CARD, @JsonProperty("plain") PLAIN }
        public enum Density { @JsonProperty("comfortable") COMFORTABLE, @JsonProperty("compact") COMPACT }
        public enum Actions { @JsonProperty("sticky") STICKY, @JsonProperty("inline") INLINE, @JsonProperty("fixed") FIXED }
        public enum Guidance { @JsonProperty("none") NONE, @JsonProperty("aside") ASIDE }
        private Width width = Width.WIDE;
        private Integer columns = 2;
        private Surface surface = Surface.CARD;
        private Density density = Density.COMFORTABLE;
        private Actions actions = Actions.STICKY;
        private Guidance guidance = Guidance.NONE;
    }

    /** Explicit business groups; unassigned fields remain in the final section. */
    private List<FormGroup> formGroups = new ArrayList<>();

    @Data
    public static class FormGroup {
        private String title;
        private List<String> fields = new ArrayList<>();
        private boolean collapsible;
        private boolean defaultCollapsed;
    }

    
    /**
     * Detail page fields to display (optional, empty means all fields)
     */
    private List<String> detailFields = new ArrayList<>();
}
