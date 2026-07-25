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
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

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
 * <p><b>Currently observing, not enforcing.</b> While the deep projection is still in place this
 * check would only duplicate it, and turning both on at once would make it impossible to tell which
 * one denied a request. Running it in observe mode first also answers the question that decides
 * whether the deeper change is safe at all: on a real workload, which commands would newly be
 * allowed to touch a target the caller cannot read? If that set is empty, the boundary is complete;
 * if it is not, the boundary is not ready and enforcement must wait.</p>
 */
@Slf4j
@Component
@Order(250)
@RequiredArgsConstructor
public class CommandTargetScopePhase implements CommandPhase {

    /** Observe (default): evaluate and record, never deny. */
    static final String MODE_OBSERVE = "observe";
    /** Enforce: deny at the boundary. Flipped together with removing the deep projection. */
    static final String MODE_ENFORCE = "enforce";

    /** The caller cannot read the record they named — a BOLA refusal. */
    static final String REASON_TARGET_NOT_READABLE = "target_record_not_readable";
    /** Off: skip entirely. Diagnostic escape hatch — costs one read per targeted command. */
    static final String MODE_OFF = "off";

    private final DynamicDataService dynamicDataService;
    private final ApplicationContext applicationContext;

    @Value("${aura.command.target-scope.mode:observe}")
    private String mode;

    @Override
    public String name() {
        return "target_scope";
    }

    @Override
    public boolean shouldSkip(CommandPipelineContext ctx) {
        if (MODE_OFF.equalsIgnoreCase(mode)) {
            return true;
        }
        CommandDefinition command = ctx.getCommand();
        return command == null
                || !StringUtils.hasText(command.getModelCode())
                || !StringUtils.hasText(ctx.getRequest().getTargetRecordId());
    }

    @Override
    public void execute(CommandPipelineContext ctx) {
        boolean enforcing = MODE_ENFORCE.equalsIgnoreCase(mode);

        Boolean readable;
        try {
            readable = evaluateReadable(ctx);
        } catch (RuntimeException ex) {
            if (enforcing) {
                // A gate that cannot evaluate must fail closed. Never downgrade an enforcing check
                // into a warning — that is how a gate quietly stops being a gate.
                throw ex;
            }
            // While only observing, this phase must not be able to break the command it is
            // observing: the target may be addressed in a way this lookup does not resolve, or the
            // permission beans may be absent in a narrowed context. Those are reasons to have no
            // observation, not reasons to fail a command that would otherwise have succeeded.
            log.warn("Boundary target-scope observation failed; command unaffected: command={} error={}",
                    ctx.getCommandCode(), ex.toString());
            return;
        }

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
        // regardless of this phase's own observe/enforce mode — the plan captures what was DECIDED,
        // not whether this phase happened to throw immediately.
        ctx.recordPhaseDecision(
                CommandPermitPlan.PhaseDecision.deny(REASON_TARGET_NOT_READABLE, name()));
        log.info("Boundary target-scope check would deny: command={} model={} record={} mode={}",
                ctx.getCommandCode(), ctx.getCommand().getModelCode(),
                ctx.getRequest().getTargetRecordId(), mode);
        if (enforcing) {
            throw new BusinessException(ResponseCode.FORBIDDEN,
                    "Access denied: you do not have permission to view this record");
        }
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
        if (tenantId == null || userId == null) {
            return null;
        }
        TenantMember member = applicationContext.getBean(TenantMemberService.class)
                .findByTenantIdAndUserId(tenantId, userId);
        return member == null ? null : member.getId();
    }
}
