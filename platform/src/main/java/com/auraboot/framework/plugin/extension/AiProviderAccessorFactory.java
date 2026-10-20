package com.auraboot.framework.plugin.extension;

/** Creates a tenant-bound provider accessor for a command-handler invocation. */
@FunctionalInterface
public interface AiProviderAccessorFactory {
    AiProviderAccessor create(Long tenantId);
}
