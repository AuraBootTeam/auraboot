package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.service.impl.pipeline.CommandAuthorizationVerdict;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPermitPlan;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.permission.service.RecordShareService;
import com.auraboot.framework.meta.service.DynamicDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces command-level permissions declared in executionConfig.permissions, and records what it
 * decided on the context as a {@link CommandAuthorizationVerdict}.
 *
 * <p>The verdict changes no behaviour here — the same calls are allowed and denied as before. It
 * exists because "did not throw" is not the same statement as "authorized": a command declaring no
 * permissions leaves this phase without having granted anything, and only an explicit verdict lets
 * later stages tell those apart.</p>
 */
@Slf4j
@Component
@Order(200)
@RequiredArgsConstructor
public class CommandAuthorizationPhase implements CommandPhase {

    private final UserPermissionService userPermissionService;
    private final RecordShareService recordShareService;
    private final ObjectProvider<DynamicDataService> dynamicDataServiceProvider;

    /**
     * Command codes already reported as undeclared. Bounded by the number of distinct commands, and
     * logging one line per execution would drown the signal it is meant to surface.
     */
    private final Set<String> reportedUndeclared = ConcurrentHashMap.newKeySet();

    @Override
    public String name() {
        return "authorization";
    }

    @Override
    public void execute(CommandPipelineContext ctx) {
        List<String> requiredPermissions = extractPermissions(ctx.getExecConfig().get("permissions"));
        if (requiredPermissions.isEmpty()) {
            reportUndeclared(ctx.getCommandCode());
            applyVerdict(ctx, CommandAuthorizationVerdict
                    .notApplicable(CommandAuthorizationVerdict.REASON_NO_DECLARED_PERMISSIONS));
            return;
        }

        Long userId = ctx.getUserId();
        if (userId == null) {
            String externalPermission = com.auraboot.framework.application.tenant.MetaContext
                    .getExternalCommandPermission();
            if (externalPermission != null && requiredPermissions.contains(externalPermission)) {
                applyVerdict(ctx, CommandAuthorizationVerdict.authorized(externalPermission));
                return;
            }
            log.warn("Skipping command permission check because userId is absent: command={}",
                    ctx.getCommandCode());
            applyVerdict(ctx, CommandAuthorizationVerdict
                    .notApplicable(CommandAuthorizationVerdict.REASON_NO_USER_CONTEXT));
            return;
        }

        for (String permission : requiredPermissions) {
            if (userPermissionService.hasPermission(userId, permission)) {
                applyVerdict(ctx, CommandAuthorizationVerdict.authorized(permission));
                return;
            }
        }

        String recordSharePermission = authorizeDeclaredRecordShare(ctx);
        if (recordSharePermission != null) {
            applyVerdict(ctx, CommandAuthorizationVerdict.authorized(recordSharePermission));
            return;
        }

        // Record the refusal as a decision before throwing: a denial that only ever surfaced as a
        // thrown exception left no trace on the context for an audit trail (or a future decision plan)
        // to read. The throw still aborts the pipeline exactly as before — this changes nothing about
        // enforcement, it only stops the "why" from being silent.
        applyVerdict(ctx, CommandAuthorizationVerdict.denied(requiredPermissions));
        throw new BusinessException(ResponseCode.FORBIDDEN,
                "Command permission denied: required one of " + String.join(", ", requiredPermissions));
    }

    /**
     * An explicit command policy may let an update collaborator execute this command for one
     * shared aggregate. The command must name the target record, and child-record commands must
     * declare the field that points back to the shared root. Missing or malformed metadata denies.
     */
    private String authorizeDeclaredRecordShare(CommandPipelineContext ctx) {
        Object rawParams = ctx.getExecConfig().get("handlerParams");
        if (!(rawParams instanceof java.util.Map<?, ?> handlerParams)) return null;
        Object rawGrant = handlerParams.get("recordShareGrant");
        if (!(rawGrant instanceof java.util.Map<?, ?> grant)) return null;

        String resourceCode = text(grant.get("resourceCode"));
        String action = text(grant.get("action"));
        String targetRecordId = ctx.getRequest().getTargetRecordId();
        if (!StringUtils.hasText(resourceCode)
                || !java.util.Set.of("read", "update").contains(action)
                || !StringUtils.hasText(targetRecordId)
                || ctx.getTenantId() == null) {
            return null;
        }

        String rootRecordPid = targetRecordId;
        String targetReferenceField = text(grant.get("targetReferenceField"));
        if (StringUtils.hasText(targetReferenceField)) {
            if (ctx.getCommand() == null || !StringUtils.hasText(ctx.getCommand().getModelCode())) {
                return null;
            }
            java.util.Map<String, Object> target = MetaContext.runWithCommandPermitScope(
                    "ALL",
                    () -> dynamicDataServiceProvider.getObject().getById(
                            ctx.getCommand().getModelCode(), targetRecordId));
            if (target == null) return null;
            Object reference = target.get(targetReferenceField);
            rootRecordPid = reference == null ? null : String.valueOf(reference).trim();
        }
        if (!StringUtils.hasText(rootRecordPid)) return null;

        Long memberId = MetaContext.getCurrentMemberId();
        String memberPid = MetaContext.getCurrentUserPid();
        boolean shared = recordShareService.isSharedByPid(
                ctx.getTenantId(), resourceCode, rootRecordPid, memberId, memberPid, action);
        return shared ? "record-share:" + resourceCode + ":" + action : null;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    /**
     * Set the verdict and, in the same breath, record the equivalent {@link CommandPermitPlan.PhaseDecision}
     * for the plan's deny-overrides combination (architecture §11.15). The verdict is this phase's public
     * finding; the phase decision is the generic form the {@link CommandPermitPlan} aggregates across
     * phases. Shadow — recording it consumes nothing and changes no behaviour.
     */
    private void applyVerdict(CommandPipelineContext ctx, CommandAuthorizationVerdict verdict) {
        ctx.setAuthorizationVerdict(verdict);
        ctx.recordPhaseDecision(toPhaseDecision(verdict));
    }

    private CommandPermitPlan.PhaseDecision toPhaseDecision(CommandAuthorizationVerdict verdict) {
        return switch (verdict.outcome()) {
            case AUTHORIZED -> CommandPermitPlan.PhaseDecision.permit(name());
            case DENIED -> CommandPermitPlan.PhaseDecision.deny(verdict.reason(), name());
            case NOT_APPLICABLE -> CommandPermitPlan.PhaseDecision.abstain(name());
        };
    }

    /**
     * A command with a handler but no declared permissions passes this phase without any check
     * having happened. That is invisible today and is exactly the population that has to shrink
     * before downstream stages can inherit the boundary's authority — so say it out loud, once.
     */
    private void reportUndeclared(String commandCode) {
        if (commandCode != null && reportedUndeclared.add(commandCode)) {
            log.warn("Command declares no permissions, so no authorization decision was made: command={}",
                    commandCode);
        }
    }

    private List<String> extractPermissions(Object rawPermissions) {
        if (!(rawPermissions instanceof List<?> values) || values.isEmpty()) {
            return List.of();
        }

        List<String> permissions = new ArrayList<>();
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                permissions.add(String.valueOf(value));
            }
        }
        return permissions;
    }
}
