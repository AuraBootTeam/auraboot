package com.auraboot.framework.permission.dto;

import java.util.List;

/** Binding changes only; shared policy definitions are never rewritten by a role draft. */
public record RoleFieldPolicyPreview(List<RoleFieldPolicyView> added, List<RoleFieldPolicyView> removed) {}
