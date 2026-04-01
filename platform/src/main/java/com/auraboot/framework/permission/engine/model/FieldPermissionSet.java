package com.auraboot.framework.permission.engine.model;

import java.util.Set;

/**
 * Field permission set — defines which fields a member can view, edit, or must be hidden.
 *
 * @param viewableFields fields the member can see
 * @param editableFields fields the member can edit
 * @param hiddenFields   fields completely hidden from the member
 */
public record FieldPermissionSet(
        Set<String> viewableFields,
        Set<String> editableFields,
        Set<String> hiddenFields
) {

    /**
     * Create a fully permissive set where all fields are viewable and editable.
     *
     * @param allFields all field codes
     * @return field permission set with full access
     */
    public static FieldPermissionSet allAllowed(Set<String> allFields) {
        return new FieldPermissionSet(allFields, allFields, Set.of());
    }
}
