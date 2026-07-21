package com.auraboot.framework.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.agent.service.AgentOrganizationService;
import com.auraboot.framework.agent.service.SystemAgentUserProvisioner;
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
    private static final String MODEL_DEPARTMENT = "org_department";
    private static final String MODEL_POSITION = "org_position";

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
    private final SystemAgentUserProvisioner systemAgentUserProvisioner;

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

        // 1. Resolve user for the service member, provisioning one if the agent has none.
        //    Only AgentTemplateSeeder used to set system_user_id, and only for the system tenant,
        //    so every tenant-created agent arrived here with null and was refused — the enrollment
        //    path was unreachable for exactly the agents tenants actually build.
        Long systemUserId = agent.getSystemUserId();
        if (systemUserId == null) {
            systemUserId = systemAgentUserProvisioner.ensureSystemAgentUser(
                    agent.getAgentCode(), agent.getName());
            agent.setSystemUserId(systemUserId);
            agentDefinitionMapper.updateById(agent);
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

        // 2. Clear agent_definition.employee_id.
        //
        // Deliberately an explicit SET rather than updateById: MyBatis-Plus's
        // default field strategy omits null values from the generated UPDATE, so
        // `setEmployeeId(null); updateById(agent)` produced a statement that did
        // not mention the column at all — the link survived every removal. Nothing
        // downstream noticed: the DELETE answered 200, the employee row really was
        // deactivated, and the log said "Agent removed from org". Only the page
        // disagreed, because getOrgPlacement still saw a non-null employee_id and
        // went on reporting the colleague as a digital employee sitting in a
        // department that no longer employed it.
        agentDefinitionMapper.update(null, new LambdaUpdateWrapper<AgentDefinition>()
                .eq(AgentDefinition::getId, agentId)
                .set(AgentDefinition::getEmployeeId, null));

        log.info("Agent removed from org: agentId={}, former employeeId={}", agentId, employeeId);
    }

    @Override
    public OrgPlacement getOrgPlacement(Long agentId) {
        AgentDefinition agent = agentDefinitionMapper.selectById(agentId);
        if (agent == null || agent.getEmployeeId() == null) {
            return OrgPlacement.notEnrolled();
        }
        Map<String, Object> employee = findEmployeeById(agent.getEmployeeId());
        if (employee == null) {
            // The link says enrolled but the row is gone — deactivated, or removed by hand. Report
            // it as not enrolled rather than as enrolled-with-blank-fields, so the page offers the
            // action that will actually work.
            return OrgPlacement.notEnrolled();
        }
        return new OrgPlacement(
                true,
                (String) employee.get("pid"),
                displayName(MODEL_DEPARTMENT, employee.get(EMP_DEPT_ID), "org_dept_name"),
                displayName(MODEL_POSITION, employee.get(EMP_POSITION_ID), "org_pos_name"));
    }

    private Map<String, Object> findEmployeeById(Long employeeId) {
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
        return result != null && result.getRecords() != null && !result.getRecords().isEmpty()
                ? result.getRecords().get(0)
                : null;
    }

    /** Resolves a referenced record's display name, or null — a missing name is not an error here. */
    private String displayName(String modelCode, Object pid, String nameField) {
        if (pid == null || String.valueOf(pid).isBlank()) {
            return null;
        }
        Map<String, Object> record = dynamicDataService.getById(modelCode, String.valueOf(pid));
        return record != null ? (String) record.get(nameField) : null;
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
