package com.auraboot.framework.im.service;

import com.auraboot.framework.im.model.ImMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Preserves the IM mention and asynchronous dispatch boundary. */
@Service
@RequiredArgsConstructor
public class ImAiService {
    @org.springframework.lang.Nullable
    private final ImAiResponder responder;

    public boolean hasMention(ImMessage message) {
        if (message.getMentions() == null) return false;
        return message.getMentions().toLowerCase().contains("\"ai\"");
    }

    public void requireAvailable() {
        if (responder == null) {
            throw new com.auraboot.framework.exception.BusinessException(
                    com.auraboot.framework.common.constant.ResponseCode.UnsupportedFeature,
                    "AI messaging is not installed");
        }
    }

    @Async("eventTaskExecutor")
    public void generateResponse(ImMessage userMessage, Long tenantId) {
        requireAvailable();
        responder.generateResponse(userMessage, tenantId);
    }
}
