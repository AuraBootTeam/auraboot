package com.auraboot.plugins.gamma;

import com.auraboot.framework.plugin.api.PluginDisableContext;
import com.auraboot.framework.plugin.api.PluginEnableContext;
import com.auraboot.framework.plugin.api.PluginInstallContext;
import com.auraboot.framework.plugin.api.PluginUninstallContext;
import com.auraboot.framework.plugin.pf4j.AuraPlugin;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PF4J entry point for the gamma-conformance dogfood plugin.
 *
 * <p>Exercises the {@code RestEndpointExtension} SPI end-to-end (auth + tenant-context
 * injection via the platform dispatcher at {@code /api/ext/probe/**}). Kept permanently as a
 * conformance regression for the SPI — not a throwaway.
 */
public class GammaConformancePlugin extends AuraPlugin {

    private static final Logger log = LoggerFactory.getLogger(GammaConformancePlugin.class);

    public GammaConformancePlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getNamespace() {
        return "probe";
    }

    @Override
    protected void doInstall(PluginInstallContext context) throws Exception {
        log.info("gamma-conformance plugin installed (freshInstall={})", context.isFreshInstall());
        context.reportProgress(100, "gamma-conformance installed");
    }

    @Override
    protected void doEnable(PluginEnableContext context) throws Exception {
        log.info("gamma-conformance plugin enabled");
    }

    @Override
    protected void doDisable(PluginDisableContext context) throws Exception {
        log.info("gamma-conformance plugin disabled");
    }

    @Override
    protected void doUninstall(PluginUninstallContext context) throws Exception {
        log.info("gamma-conformance plugin uninstalled");
    }
}
