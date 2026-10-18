package com.auraboot.framework.application.bootstrap;

/** Optional module initialization, invoked only by the explicit bootstrap flow. */
@FunctionalInterface
public interface PlatformSeedContributor {
    void seed();
}
