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
    private final AnnouncementReleaseService releases;
    private final AnnouncementApprovalService approvals;
    private final AnnouncementOperatingPolicyService operatingPolicies;
    private final AnnouncementStatisticsExportCommand statisticsExports;

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
        if(AnnouncementStatisticsExportCommand.CODE.equals(context.getCommandCode()))
            return statisticsExports.authorizeTarget(context.getTargetRecordId(),context.getPayload());
        if (AnnouncementOperatingPolicyService.UPDATE.equals(context.getCommandCode())) return operatingPolicies.authorizeTarget();
        if (isApproval(context.getCommandCode())) return approvals.authorizeTarget(context.getCommandCode(),context.getTargetRecordId());
        if (isRelease(context.getCommandCode())) return releases.authorizeTarget(context.getTargetRecordId());
        return lifecycle.authorizeTarget(context.getCommandCode(), context.getTargetRecordId());
    }

    private static boolean isApproval(String code) {
        return AnnouncementApprovalService.SUBMIT.equals(code) || AnnouncementApprovalService.WITHDRAW.equals(code);
    }

    private static boolean isRelease(String code) {
        return AnnouncementReleaseService.PUBLISH.equals(code) || AnnouncementReleaseService.CANCEL.equals(code);
    }

    @Override
    public Map<String, Object> execute(CommandHandlerContext context) {
        if(AnnouncementStatisticsExportCommand.CODE.equals(context.getCommandCode()))
            return statisticsExports.execute(context.getTargetRecordId(),context.getPayload(),context.getClientRequestId());
        if (AnnouncementOperatingPolicyService.UPDATE.equals(context.getCommandCode())) return operatingPolicies.execute(context.getPayload(),context.getClientRequestId());
        if (isApproval(context.getCommandCode())) return approvals.execute(context.getCommandCode(),context.getTargetRecordId(),
                context.getPayload(),context.getClientRequestId(),context.getExpectedVersion());
        if (isRelease(context.getCommandCode())) return releases.execute(context.getCommandCode(),context.getTargetRecordId(),
                context.getPayload(),context.getClientRequestId(),context.getExpectedVersion());
        return lifecycle.execute(
                context.getCommandCode(),
                context.getTargetRecordId(),
                context.getPayload(),
                context.getClientRequestId(),
                context.getExpectedVersion());
    }
}
