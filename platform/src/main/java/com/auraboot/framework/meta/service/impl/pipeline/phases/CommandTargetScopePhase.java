package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPermitPlan;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.permission.engine.model.PermissionResult;
import com.auraboot.framework.permission.service.PermissionFacade;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Optional;

/**
 * Decides, at the boundary, whether the caller may act on the record they NAMED in the request.
 *
 * <p>Authorization for a command currently happens twice at different depths: this pipeline checks
 * whether the caller may run the command, and then the data layer separately re-checks the caller's
 * record-level read for every row the handler happens to touch. The second check does not know the
 * first one happened, which is how a caller authorized to run price sourcing had the sourcing's own
 * bookkeeping write refused (2026-07-22).</p>
 *
 * <p>The fix is to stop projecting the caller's read permission onto rows the handler DERIVES. But
 * that projection is also, incidentally, what stops a caller from acting on a record they cannot
 * see — and that protection must not be lost with it. So it moves here, where the request names the
 * record, and where "which record" is still a meaningful question. Rows the handler derives for
 * itself (evidence, audit, roll-ups) are internal bookkeeping and are deliberately out of scope.</p>
 *
 * <p><b>This is an enforcing gate.</b> It runs before idempotency lookup, so a caller whose row
 * access was revoked cannot use a previously known request key to retrieve a cached result. The
 * former observe/off migration modes were intentionally removed once command permit plans became
 * authoritative: an authorization gate cannot safely be configurable as fail-open.</p>
 */
@Slf4j
@Component
@Order(250)
@RequiredArgsConstructor
public class CommandTargetScopePhase implements CommandPhase {

    /** The caller cannot read the record they named — a BOLA refusal. */
    static final String REASON_TARGET_NOT_READABLE = "target_record_not_readable";

    private final DynamicDataService dynamicDataService;
    private final ApplicationContext applicationContext;
    private final PlatformTransactionManager transactionManager;
    private final ExtensionRegistry extensionRegistry;

    @Override
    public String name() {
        return "target_scope";
    }

    @Override
    public boolean shouldSkip(CommandPipelineContext ctx) {
        CommandDefinition command = ctx.getCommand();
        return command == null
                || !StringUtils.hasText(command.getModelCode())
                || !StringUtils.hasText(ctx.getRequest().getTargetRecordId())
                || isHandlerManagedTarget(ctx);
    }

    private boolean isHandlerManagedTarget(CommandPipelineContext ctx) {
        String handlerCode = resolveHandlerCode(ctx);
        return Optional.ofNullable(extensionRegistry)
                .flatMap(registry -> registry.getCommandHandler(handlerCode))
                .map(handler -> {
                    boolean requiresPersistence = handler.requiresDslPersistence(
                            handlerCode, ctx.getExecConfig(), ctx.getRequest());
                    ctx.setHasPluginHandler(true);
                    ctx.setPluginRequiresDslPersistence(requiresPersistence);
                    return !requiresPersistence;
                })
                .orElse(false);
    }

    private String resolveHandlerCode(CommandPipelineContext ctx) {
        Map<String, Object> config = ctx.getExecConfig();
        if (config != null) {
            Object handler = config.get("handler");
            if (handler instanceof String handlerCode && StringUtils.hasText(handlerCode)) {
                return handlerCode.trim();
            }
        }
        return ctx.getCommandCode();
    }

    @Override
    public void execute(CommandPipelineContext ctx) {
        // An authorization gate that cannot evaluate must fail closed. This includes lookup,
        // membership and permission-engine failures; none may degrade into an idempotent replay.
        Boolean readable = evaluateReadableInIsolatedTransaction(ctx);

        if (readable == null) {
            // No subject, or the named record does not exist — no authorization decision to make.
            ctx.recordPhaseDecision(CommandPermitPlan.PhaseDecision.abstain(name()));
            return;
        }
        ctx.setTargetRecordReadable(readable);
        if (readable) {
            // A gate, not a grant: a readable target means this phase has no objection, not that it
            // authorizes. Only the RBAC capability phase (@200) emits PERMIT; if this phase granted,
            // an undeclared command on any record the caller can see would read as authorized —
            // conflating "can see" with "may act", the exact projection the 2026-07-22 incident came
            // from. So it abstains.
            ctx.recordPhaseDecision(CommandPermitPlan.PhaseDecision.abstain(name()));
            return;
        }

        // The caller cannot see the record they named. Record the refusal for the permit plan
        // before throwing so the complete boundary decision remains auditable.
        ctx.recordPhaseDecision(
                CommandPermitPlan.PhaseDecision.deny(REASON_TARGET_NOT_READABLE, name()));
        log.info("Boundary target-scope check denied: command={} model={} record={}",
                ctx.getCommandCode(), ctx.getCommand().getModelCode(),
                ctx.getRequest().getTargetRecordId());
        throw new BusinessException(ResponseCode.FORBIDDEN,
                "Access denied: you do not have permission to view this record");
    }

    /**
     * Keep a boundary read failure from poisoning the outer command transaction.
     *
     * <p>PostgreSQL marks the whole transaction aborted after a statement error. Catching the
     * resulting exception is therefore not enough: the outer transaction would remain aborted.
     * A new transaction gives the boundary read its own rollback boundary; the exception is then
     * rethrown and the gate fails closed without contaminating unrelated transaction state.</p>
     */
    private Boolean evaluateReadableInIsolatedTransaction(CommandPipelineContext ctx) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> evaluateReadable(ctx));
    }

    /**
     * @return whether the caller may read the named target, or null when there is nothing to decide
     *         (no subject in context, or the target does not resolve).
     */
    private Boolean evaluateReadable(CommandPipelineContext ctx) {
        String modelCode = ctx.getCommand().getModelCode();
        String recordId = ctx.getRequest().getTargetRecordId();

        // Read the record itself out of the caller's projection: the boundary has to SEE the row to
        // judge it, and reading it through the very gate we are evaluating would be circular.
        Map<String, Object> record = MetaContext.runWithCommandPermitScope("ALL",
                () -> dynamicDataService.getById(modelCode, recordId));
        if (record == null) {
            // A missing record is not an authorization answer — later phases surface "not found".
            return null;
        }
        ctx.setTargetRecordVersion(resolveRecordVersion(record));

        Long memberId = resolveMemberId(ctx);
        if (memberId == null) {
            // No subject to evaluate (system/scheduled invocation), but D5 still retains the
            // server-loaded target version for the write that follows.
            return null;
        }

        PermissionResult result = applicationContext.getBean(PermissionFacade.class)
                .canOperate(memberId, modelCode, "read", record);
        if (!result.granted()) {
            log.debug("target-scope deny reason: command={} reason={}", ctx.getCommandCode(), result.reason());
        }
        return result.granted();
    }

    private Long resolveRecordVersion(Map<String, Object> record) {
        Object version = record.get("row_version");
        if (version instanceof Number number) {
            return number.longValue();
        }
        if (version instanceof String text && StringUtils.hasText(text)) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                // Unversioned/malformed rows keep D5 unresolved; normal validation owns bad fields.
            }
        }
        return null;
    }

    private Long resolveMemberId(CommandPipelineContext ctx) {
        Long memberId = MetaContext.getCurrentMemberId();
        if (memberId != null) {
            return memberId;
        }
        Long tenantId = ctx.getTenantId();
        Long userId = ctx.getUserId();
        if (userId == null) {
            // A true system/scheduled invocation has no user subject to evaluate.
            return null;
        }
        if (tenantId == null) {
            throw new BusinessException(ResponseCode.FORBIDDEN,
                    "Access denied: tenant membership context is missing");
        }
        TenantMember member = applicationContext.getBean(TenantMemberService.class)
                .findByTenantIdAndUserId(tenantId, userId);
        if (member == null || member.getId() == null) {
            throw new BusinessException(ResponseCode.FORBIDDEN,
                    "Access denied: active tenant membership is required");
        }
        return member.getId();
    }
}
