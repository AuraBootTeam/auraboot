package com.auraboot.framework.saas.bootstrap;

/**
 * Extends the first-install pipeline after core tenants, roles, and menus exist
 * but before {@code system.initialized=true} is committed.
 */
@FunctionalInterface
public interface BootstrapPostProcessor {

    void process(BootstrapContext context);
}
