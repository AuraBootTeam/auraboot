package com.auraboot.framework.agentchat.employee;

import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/im")
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
