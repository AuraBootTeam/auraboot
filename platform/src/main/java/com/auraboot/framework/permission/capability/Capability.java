package com.auraboot.framework.permission.capability;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A resolved capability for the permission v2 UI: a business-language bundle of permission codes
 * with a granted flag (true when the role/subject already holds every included code).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Capability {

    private String code;
    private String group;
    private String label;
    private boolean sensitive;
    private List<String> includes;
    /** True when every code in {@link #includes} is currently granted to the subject. */
    private boolean granted;
    /** True when this capability was auto-derived from code convention rather than declared. */
    private boolean conventionDerived;
}
