package com.auraboot.framework.meta.dto;

/** A detached, tenant-scoped definition for an explicitly published field version. */
public record PublishedFieldDefinition(Long tenantId, String pid, String code, Integer version,
                                       FieldDefinition definition) {}
