package com.auraboot.framework.agent.handler;

import com.auraboot.framework.agent.port.AgentChatPort;
import com.auraboot.framework.agent.service.AgentApprovalGateService;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Command handler for Agent approval inbox actions.
 *
 * <p>The ACP list page triggers commands through the generic command pipeline.
 * Approval actions must use the Agent approval service rather than a plain
 * state_transition so approval authorization, plan integrity checks, and
 * suspended chat-tool execution all run on the UI path.
 */
@Component
public class AgentApprovalCommandHandler implements CommandHandlerExtension {

    private static final String APPROVE_COMMAND = "acp:approve_request";
    private static final String REJECT_COMMAND = "acp:reject_request";

    private final ObjectProvider<AgentApprovalGateService> approvalGateServiceProvider;
    private final ObjectProvider<AgentChatPort> agentChatPortProvider;

    public AgentApprovalCommandHandler(ObjectProvider<AgentApprovalGateService> approvalGateServiceProvider,
                                       ObjectProvider<AgentChatPort> agentChatPortProvider) {
        this.approvalGateServiceProvider = approvalGateServiceProvider;
        this.agentChatPortProvider = agentChatPortProvider;
    }

    @Override
    public String getCommandType() {
        return APPROVE_COMMAND;
    }

    @Override
    public boolean supports(String commandType) {
        return APPROVE_COMMAND.equals(commandType) || REJECT_COMMAND.equals(commandType);
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public boolean requiresDslPersistence(
            String commandType,
            Map<String, Object> execConfig,
            com.auraboot.framework.meta.dto.CommandExecuteRequest request) {
        return false;
    }

    @Override
    public Object execute(CommandContext context) {
        String approvalPid = context.recordId();
        if (!StringUtils.hasText(approvalPid)) {
            throw new BusinessException(ResponseCode.BadParam, "Approval PID is required");
        }
        Long tenantId = context.tenantId();
        Long userId = MetaContext.exists() ? MetaContext.getCurrentUserId() : null;
        if (tenantId == null || userId == null) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "Approval requires authenticated tenant user context");
        }
        if (!approvalGateService().isAuthorizedApprover(tenantId, approvalPid, userId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "Current user is not authorized to approve this request");
        }

        if (APPROVE_COMMAND.equals(context.commandType())) {
            return approve(tenantId, approvalPid, userId);
        }
        if (REJECT_COMMAND.equals(context.commandType())) {
            return reject(tenantId, approvalPid, userId, context.payload());
        }
        throw new BusinessException(ResponseCode.BadParam, "Unsupported approval command: " + context.commandType());
    }

    private Map<String, Object> approve(Long tenantId, String approvalPid, Long userId) {
        Map<String, Object> approval = approvalGateService().approve(tenantId, approvalPid, userId);
        if (approval == null) {
            throw new BusinessException(ResponseCode.BadParam,
                    "Approval not found or not in PENDING state: " + approvalPid);
        }
        Map<String, Object> response = new LinkedHashMap<>(approval);
        Map<String, Object> chatToolResult = agentChatPort().executeApprovedPendingTool(tenantId, approvalPid);
        if (Boolean.TRUE.equals(chatToolResult.get("handled"))) {
            response.put("toolExecutionResult", chatToolResult);
        }
        return response;
    }

    private Map<String, Object> reject(Long tenantId, String approvalPid, Long userId,
                                       Map<String, Object> payload) {
        String reason = payload != null && payload.get("rejection_reason") != null
                ? String.valueOf(payload.get("rejection_reason"))
                : "Rejected by user";
        Map<String, Object> approval = approvalGateService().reject(tenantId, approvalPid, userId, reason);
        if (approval == null) {
            throw new BusinessException(ResponseCode.BadParam,
                    "Approval not found or not in PENDING state: " + approvalPid);
        }
        return new LinkedHashMap<>(approval);
    }

    private AgentApprovalGateService approvalGateService() {
        return approvalGateServiceProvider.getObject();
    }

    private AgentChatPort agentChatPort() {
        return agentChatPortProvider.getObject();
    }
}
