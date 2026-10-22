package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.openplatform.service.InstalledIntegrationExecution;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import org.springframework.security.access.AccessDeniedException;

/** Consumer-owned requirements are checked before handler effects or async submission. */
public final class CommandHandlerCapabilityGuard {
    private CommandHandlerCapabilityGuard() {}

    public static void requireAvailable(CommandHandlerExtension handler, String handlerCode,
                                        boolean aiAvailable, boolean knowledgeAvailable) {
        boolean aiRequired = handler.requiresAiProvider(handlerCode);
        boolean knowledgeRequired = handler.requiresKnowledgeBase(handlerCode);
        if ((aiRequired || knowledgeRequired) && InstalledIntegrationExecution.active()) {
            throw new AccessDeniedException("Installed integration commands cannot request AI or knowledge accessors");
        }
        if (aiRequired && !aiAvailable) {
            throw new BusinessException(ResponseCode.UnsupportedFeature, "AI provider capability is not installed");
        }
        if (knowledgeRequired && !knowledgeAvailable) {
            throw new BusinessException(ResponseCode.UnsupportedFeature, "Knowledge base capability is not installed");
        }
    }
}
