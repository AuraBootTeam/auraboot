package com.auraboot.framework.tenant.service;

/** Initializes optional AI skills during explicit tenant bootstrap. */
public interface TenantSkillInitializer {
    void initialize(Long tenantId);
    void initializeAssistant(Long tenantId);
}
