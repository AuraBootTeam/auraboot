package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.entitlement.spi.EntitlementChecker;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Check plugin and feature entitlements.
 */
@Slf4j
@Component
@Order(400)
@RequiredArgsConstructor
public class EntitlementPhase implements CommandPhase {

    private final EntitlementChecker entitlementChecker;

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
        if (modelCode == null) return;

        String namespace = modelCode.contains("_")
                ? modelCode.substring(0, modelCode.indexOf('_'))
                : modelCode;

        if (!entitlementChecker.isPluginActive(namespace)) {
            throw new BusinessException(ResponseCode.FORBIDDEN,
                    "Plugin entitlement required for command: " + ctx.getCommandCode());
        }

        String requiredFeature = ctx.getCommand().getRequiredFeature();
        if (requiredFeature != null && !requiredFeature.isEmpty()) {
            if (!entitlementChecker.hasFeature(namespace, requiredFeature)) {
                throw new BusinessException(ResponseCode.FORBIDDEN,
                        "Feature entitlement required: " + requiredFeature);
            }
        }
    }
}
