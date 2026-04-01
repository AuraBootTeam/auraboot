package com.auraboot.framework.agent.service.impl;

import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.agent.service.AgentOrganizationService;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of agent-to-organization enrollment.
 *
 * <p>Creates the following chain when enrolling an agent as a digital employee:
 * <ol>
 *   <li>org_employee record (type=ai) via DynamicDataService</li>
 *   <li>service tenant_member linked to the agent's system_user_id</li>
 *   <li>bidirectional link: employee.member_id = member.pid, member.employeeId = employee.id</li>
 *   <li>agent_definition.employee_id = employee.id</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentOrganizationServiceImpl implements AgentOrganizationService {

    private static final String MODEL_EMPLOYEE = "org_employee";

    // Employee field codes (matching OrgEmployeeServiceImpl constants)
    private static final String EMP_NAME = "org_emp_name";
    private static final String EMP_DEPT_ID = "org_emp_dept_id";
    private static final String EMP_POSITION_ID = "org_emp_position_id";
    private static final String EMP_STATUS = "org_emp_status";
    private static final String EMP_TYPE = "org_emp_type";
    private static final String EMP_MEMBER_ID = "org_emp_member_id";
    private static final String EMP_USER_ID = "org_emp_user_id";

    private final AgentDefinitionMapper agentDefinitionMapper;
    private final DynamicDataService dynamicDataService;
    private final TenantMemberService tenantMemberService;
    private final UserService userService;

    @Override
    @Transactional
    public void enrollAsEmployee(Long agentId, String departmentPid, String positionPid) {
        AgentDefinition agent = agentDefinitionMapper.selectById(agentId);
        if (agent == null) {
            throw new BusinessException("Agent not found: " + agentId);
        }
        if (agent.getEmployeeId() != null) {
            throw new BusinessException("Agent is already enrolled as employee, employeeId=" + agent.getEmployeeId());
        }

        Long tenantId = MetaContext.getCurrentTenantId();

        // 1. Resolve user for the service member.
        //    Use the agent's system_user_id if available, otherwise fail.
        Long systemUserId = agent.getSystemUserId();
        if (systemUserId == null) {
            throw new BusinessException("Agent has no system_user_id — cannot create service member without a user");
        }

        // 2. Create or find service tenant_member for this agent's system user
        TenantMember serviceMember = tenantMemberService.findByTenantIdAndUserId(tenantId, systemUserId);
        if (serviceMember == null) {
            serviceMember = tenantMemberService.addMember(systemUserId, tenantId, StatusConstants.ACTIVE);
            log.info("Created service tenant_member for agent {}: memberId={}", agent.getName(), serviceMember.getId());
        }

        // 3. Create org_employee record (type=ai)
        User systemUser = userService.findByUserId(systemUserId);
        Map<String, Object> empData = new HashMap<>();
        empData.put(EMP_NAME, agent.getName());
        empData.put(EMP_DEPT_ID, departmentPid);
        empData.put(EMP_STATUS, StatusConstants.ACTIVE);
        empData.put(EMP_TYPE, "ai");
        empData.put(EMP_MEMBER_ID, serviceMember.getPid());
        if (systemUser != null) {
            empData.put(EMP_USER_ID, systemUser.getPid());
        }
        if (positionPid != null) {
            empData.put(EMP_POSITION_ID, positionPid);
        }

        Map<String, Object> created = dynamicDataService.create(MODEL_EMPLOYEE, empData);
        Long employeeId = extractId(created);
        String employeePid = (String) created.get("pid");

        // 4. Write back: member.employeeId = employee.id
        serviceMember.setEmployeeId(employeeId);
        tenantMemberService.updateMember(serviceMember);

        // 5. Update agent_definition.employee_id
        agent.setEmployeeId(employeeId);
        agentDefinitionMapper.updateById(agent);

        log.info("Agent enrolled as digital employee: agentId={}, agentName={}, employeePid={}, memberId={}",
                agentId, agent.getName(), employeePid, serviceMember.getId());
    }

    @Override
    @Transactional
    public void removeFromOrg(Long agentId) {
        AgentDefinition agent = agentDefinitionMapper.selectById(agentId);
        if (agent == null) {
            throw new BusinessException("Agent not found: " + agentId);
        }
        if (agent.getEmployeeId() == null) {
            log.info("Agent {} is not enrolled as employee, nothing to remove", agentId);
            return;
        }

        // 1. Deactivate the org_employee record
        Long employeeId = agent.getEmployeeId();
        // DynamicDataService.getById works with PIDs, so we query by numeric ID using list
        DynamicQueryRequest queryReq = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(1)
                .conditions(List.of(QueryCondition.builder()
                        .fieldName("id")
                        .operator(QueryCondition.Operator.EQ)
                        .value(employeeId)
                        .build()))
                .build();
        PaginationResult<Map<String, Object>> result = dynamicDataService.list(MODEL_EMPLOYEE, queryReq);
        if (result != null && result.getRecords() != null && !result.getRecords().isEmpty()) {
            Map<String, Object> employee = result.getRecords().get(0);
            String employeePid = (String) employee.get("pid");
            Map<String, Object> updateData = new HashMap<>();
            updateData.put(EMP_STATUS, StatusConstants.INACTIVE);
            dynamicDataService.update(MODEL_EMPLOYEE, employeePid, updateData);
            log.info("Deactivated org_employee: pid={}", employeePid);
        }

        // 2. Clear agent_definition.employee_id
        agent.setEmployeeId(null);
        agentDefinitionMapper.updateById(agent);

        log.info("Agent removed from org: agentId={}, former employeeId={}", agentId, employeeId);
    }

    @Override
    public Long getAgentMemberId(Long agentId, Long triggerUserId) {
        AgentDefinition agent = agentDefinitionMapper.selectById(agentId);
        if (agent == null) {
            throw new BusinessException("Agent not found: " + agentId);
        }

        if (agent.getEmployeeId() != null) {
            // Independent mode: agent has its own employee → find linked service member
            Long tenantId = MetaContext.getCurrentTenantId();
            Long systemUserId = agent.getSystemUserId();
            if (systemUserId != null) {
                TenantMember serviceMember = tenantMemberService.findByTenantIdAndUserId(tenantId, systemUserId);
                if (serviceMember != null) {
                    return serviceMember.getId();
                }
            }
            log.warn("Agent {} has employee_id but no service member found, falling back to trigger user", agentId);
        }

        // Proxy mode: use the triggering user's member ID
        Long tenantId = MetaContext.getCurrentTenantId();
        TenantMember triggerMember = tenantMemberService.findByTenantIdAndUserId(tenantId, triggerUserId);
        if (triggerMember == null) {
            throw new BusinessException("Trigger user has no tenant member: userId=" + triggerUserId);
        }
        return triggerMember.getId();
    }

    private Long extractId(Map<String, Object> record) {
        Object id = record.get("id");
        if (id instanceof Long longId) {
            return longId;
        }
        if (id instanceof Number number) {
            return number.longValue();
        }
        if (id instanceof String strId) {
            return Long.parseLong(strId);
        }
        throw new BusinessException("Cannot extract employee ID from created record");
    }
}
