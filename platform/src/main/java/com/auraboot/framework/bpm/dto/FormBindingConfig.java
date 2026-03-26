package com.auraboot.framework.bpm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Form binding configuration for a BPMN node.
 * Binds a Page DSL form to a specific user task node.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormBindingConfig {

    private String nodeId;                          // BPMN node ID
    private String formType;                        // PAGE_DSL
    private String formRef;                         // Page config pid or pageCode
    private Map<String, String> fieldPermissions;   // { fieldName: EDITABLE/READONLY/HIDDEN }
    private Map<String, String> variableBindings;   // { formField: processVariable }
    private String version;                         // Optional Page version
    private String versionStrategy;                 // FIXED or LATEST
    private String saveStrategy;                    // business_only | dual_write | variable_only
    private String permissionMode;                  // merge | override (default: merge)
    private Map<String, String> builtinVariables;   // { decision: "decision", comment: "comment" }
}
