package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.entitlement.spi.EntitlementChecker;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPermitPlan;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Check plugin and feature entitlements.
 */
@Slf4j
@Component
@Order(400)
@RequiredArgsConstructor
public class EntitlementPhase implements CommandPhase {

    private final EntitlementChecker entitlementChecker;
    private final PluginRecordMapper pluginRecordMapper;

    /** The plugin owning the command's namespace is not active for this tenant. */
    static final String REASON_PLUGIN_ENTITLEMENT_REQUIRED = "plugin_entitlement_required";
    /** The command's required feature is not entitled for this tenant. */
    static final String REASON_FEATURE_ENTITLEMENT_REQUIRED = "feature_entitlement_required";

    @Override
    public String name() {
        return "entitlement_check";
    }

    @Override
    public boolean shouldSkip(CommandPipelineContext ctx) {
        return !entitlementChecker.isEnabled();
    }

    @Override
    public void execute(CommandPipelineContext ctx) {
        String modelCode = ctx.getCommand().getModelCode();
        if (modelCode == null) {
            ctx.recordPhaseDecision(CommandPermitPlan.PhaseDecision.abstain(name()));
            return;
        }

        String entitlementPluginId = resolveEntitlementPluginId(ctx, modelCode);

        if (!entitlementChecker.isPluginActive(entitlementPluginId)) {
            // A gate: entitlement can refuse, but never grants. Record the refusal for the permit
            // plan before throwing so the boundary decision remains auditable.
            ctx.recordPhaseDecision(
                    CommandPermitPlan.PhaseDecision.deny(REASON_PLUGIN_ENTITLEMENT_REQUIRED, name()));
            throw new BusinessException(ResponseCode.FORBIDDEN,
                    "Plugin entitlement required for command: " + ctx.getCommandCode());
        }

        String requiredFeature = ctx.getCommand().getRequiredFeature();
        if (requiredFeature != null && !requiredFeature.isEmpty()) {
            if (!entitlementChecker.hasFeature(entitlementPluginId, requiredFeature)) {
                ctx.recordPhaseDecision(
                        CommandPermitPlan.PhaseDecision.deny(REASON_FEATURE_ENTITLEMENT_REQUIRED, name()));
                throw new BusinessException(ResponseCode.FORBIDDEN,
                        "Feature entitlement required: " + requiredFeature);
            }
        }

        // Entitlement satisfied — the gate has no objection, but it does not grant.
        ctx.recordPhaseDecision(CommandPermitPlan.PhaseDecision.abstain(name()));
    }

    private String resolveEntitlementPluginId(CommandPipelineContext ctx, String modelCode) {
        String pluginPid = ctx.getCommand().getPluginPid();
        if (StringUtils.hasText(pluginPid)) {
            PluginRecord owner = pluginRecordMapper.findByPid(pluginPid);
            if (owner != null && StringUtils.hasText(owner.getPluginId())) {
                return owner.getPluginId();
            }
            log.warn("Command {} references missing plugin owner pid {}; falling back to model namespace",
                    ctx.getCommandCode(), pluginPid);
        }
        return modelCode.contains("_")
                ? modelCode.substring(0, modelCode.indexOf('_'))
                : modelCode;
    }
}
