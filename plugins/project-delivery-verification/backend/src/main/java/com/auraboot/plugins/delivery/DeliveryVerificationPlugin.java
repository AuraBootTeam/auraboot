package com.auraboot.plugins.delivery;

import com.auraboot.framework.plugin.api.*;
import com.auraboot.framework.plugin.pf4j.AuraPlugin;
import org.pf4j.PluginWrapper;

/** Optional business extension; the underlying project template remains config-only. */
public final class DeliveryVerificationPlugin extends AuraPlugin {
    public DeliveryVerificationPlugin(PluginWrapper wrapper) { super(wrapper); }
    @Override public String getNamespace() { return "tdv"; }
    @Override protected void doInstall(PluginInstallContext context) { }
    @Override protected void doEnable(PluginEnableContext context) { }
    @Override protected void doDisable(PluginDisableContext context) { }
    @Override protected void doUninstall(PluginUninstallContext context) { }
}
