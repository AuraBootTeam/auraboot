package com.auraboot.plugins.crm;

import com.auraboot.framework.plugin.api.*;
import com.auraboot.framework.plugin.pf4j.AuraPlugin;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Official CRM hybrid backend plugin.
 *
 * <p>The CRM domain is predominantly config-only (models / commands / pages),
 * but the M4 lead engine carries multi-step business logic that does not reduce
 * to declarative side-effects:
 * <ul>
 *   <li>{@link com.auraboot.plugins.crm.handler.RescoreLeadHandler} —
 *       evaluates every active {@code crm_lead_score_rule} against a lead and
 *       sums the matching points into {@code crm_lead_score}.</li>
 *   <li>{@link com.auraboot.plugins.crm.handler.AutoAssignLeadHandler} —
 *       selects the highest-priority matching {@code crm_assignment_rule}
 *       (round-robin / territory / load) and writes {@code crm_lead_assigned_to}.</li>
 * </ul>
 *
 * <p>Both handlers fail loud on missing configuration (red line §8): no
 * self-healing, no silent fallback to a default rep / zero score.
 */
public class CrmPlugin extends AuraPlugin {

    private static final Logger log = LoggerFactory.getLogger(CrmPlugin.class);

    public CrmPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getNamespace() {
        return "crm";
    }

    @Override
    protected void doInstall(PluginInstallContext context) throws Exception {
        context.reportProgress(100, "CRM plugin installed");
    }

    @Override
    protected void doEnable(PluginEnableContext context) throws Exception {
        log.info("CRM plugin enabled (automation and analytics handlers active)");
    }

    @Override
    protected void doDisable(PluginDisableContext context) throws Exception {
        log.info("CRM plugin disabled");
    }

    @Override
    protected void doUninstall(PluginUninstallContext context) throws Exception {
        log.info("CRM plugin uninstalled");
    }
}
