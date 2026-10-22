package com.auraboot.framework.agentchat.employee;

import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Lists the tenant's configured agent "employees" (the AI counterparts a member can
 * start a chat with). This is a tenant-shared catalog every member legitimately needs
 * to see — it exposes no per-user private data — so authentication alone is the
 * complete access-control story. Marked {@link AuthenticatedAccess} instead of leaving
 * it un-annotated (which the interceptor shadow-allows) so the read surface is an
 * explicit, reviewed decision.
 */
@RestController
@RequestMapping("/api/im")
@AuthenticatedAccess("tenant-shared agent-employee catalog visible to any authenticated member; "
        + "no per-user data, no RBAC permission applies")
public class AgentEmployeeController {

    private final AgentEmployeeService agentEmployeeService;

    public AgentEmployeeController(AgentEmployeeService agentEmployeeService) {
        this.agentEmployeeService = agentEmployeeService;
    }

    @GetMapping("/agent-employees")
    public ApiResponse<List<AgentDefinition>> listAgentEmployees() {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<AgentDefinition> employees = agentEmployeeService.listEmployees(tenantId);
        return ApiResponse.ok(employees);
    }
}
