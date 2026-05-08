package com.auraboot.plugins.workflowdemo;

import com.auraboot.framework.plugin.api.PluginDisableContext;
import com.auraboot.framework.plugin.api.PluginEnableContext;
import com.auraboot.framework.plugin.api.PluginInstallContext;
import com.auraboot.framework.plugin.api.PluginUninstallContext;
import com.auraboot.framework.plugin.pf4j.AuraPlugin;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorkflowDemoPlugin extends AuraPlugin {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDemoPlugin.class);

    public WorkflowDemoPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getNamespace() {
        return "wd";
    }

    @Override
    protected void doInstall(PluginInstallContext context) throws Exception {
        context.reportProgress(100, "Workflow demo plugin installed");
    }

    @Override
    protected void doEnable(PluginEnableContext context) throws Exception {
        log.info("Workflow demo plugin enabled (P1 ACP platformization slice)");
    }

    @Override
    protected void doDisable(PluginDisableContext context) throws Exception {
        log.info("Workflow demo plugin disabled");
    }

    @Override
    protected void doUninstall(PluginUninstallContext context) throws Exception {
        log.info("Workflow demo plugin uninstalled");
    }
}
