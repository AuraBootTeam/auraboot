package com.auraboot.framework.plugin.extension;

import org.pf4j.ExtensionPoint;
import java.util.List;

/** Registers exact plugin-owned physical sources for tenant-scoped named queries. */
public interface NamedQueryNativeSourceExtension extends ExtensionPoint {
    /** Active plugins only; every source requires a tenant column and read authorization. */
    List<Source> sources();

    /** The resource is checked with the read action; prefixes and wildcard tables are unsupported. */
    record Source(String qualifiedTable, String readResourceCode) { }
}
