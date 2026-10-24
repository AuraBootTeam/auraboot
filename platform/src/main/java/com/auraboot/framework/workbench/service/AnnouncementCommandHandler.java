package com.auraboot.framework.workbench.service;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.service.CommandHandler;
import com.auraboot.framework.meta.service.CommandHandlerContext;
import com.auraboot.framework.meta.service.DryRunSafe;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.util.Map;

/** Every announcement command uses the same transactional domain writer. */
@Component("announcementCommandHandler")
@RequiredArgsConstructor
@DryRunSafe
public class AnnouncementCommandHandler
        implements CommandHandler, com.auraboot.framework.meta.service.CommandTargetAuthorizer {
    private final AnnouncementLifecycleService lifecycle;

    @Override
    public String getHandlerName() {
        return "announcementCommandHandler";
    }

    @Override
    public boolean requiresDslPersistence(
            String code, Map<String, Object> config, CommandExecuteRequest request) {
        return false;
    }

    @Override
    public long authorizeTarget(CommandHandlerContext context) {
        return lifecycle.authorizeTarget(context.getCommandCode(), context.getTargetRecordId());
    }

    @Override
    public Map<String, Object> execute(CommandHandlerContext context) {
        return lifecycle.execute(
                context.getCommandCode(),
                context.getTargetRecordId(),
                context.getPayload(),
                context.getClientRequestId(),
                context.getExpectedVersion());
    }
}
