package com.auraboot.framework.plugin.extension;

import org.pf4j.ExtensionPoint;

import java.util.List;

/**
 * Declares a product-owned Spring application module to the AuraBoot host.
 *
 * <p>The host creates an isolated child application context whose parent is
 * the platform context. Product implementations therefore keep their own
 * lifecycle and class loader while consuming only versioned platform
 * contracts and runtime services. The platform never scans product packages.
 */
public interface ApplicationModuleExtension extends ExtensionPoint {

    /** Stable module identity used in diagnostics and duplicate detection. */
    String moduleId();

    /** Configuration roots registered in the isolated child context. */
    List<Class<?>> configurationTypes();
}
