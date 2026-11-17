package com.auraboot.framework.permission.dto;

/** A role binding summary; policy expressions and other roles' bindings are not exposed. */
public record RoleFieldPolicyView(String policyPid, String name, String modelCode, String fieldCode,
                                 String policyType, String maskType, boolean enabled) {}
