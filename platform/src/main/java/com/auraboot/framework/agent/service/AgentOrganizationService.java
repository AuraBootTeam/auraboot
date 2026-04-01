package com.auraboot.framework.agent.service;

/**
 * Manages the lifecycle of AI agents as digital employees in the organization.
 *
 * <p>Bridges the agent domain (ab_agent_definition) with the organization domain
 * (mt_org_employee + ab_tenant_member), allowing agents to appear in org charts
 * and have their own permission scope.</p>
 */
public interface AgentOrganizationService {

    /**
     * Enroll an agent as a digital employee.
     *
     * <p>Creates an org_employee record (type=ai) and a service tenant_member,
     * then links them bidirectionally and sets agent_definition.employee_id.</p>
     *
     * @param agentId       the agent definition ID
     * @param departmentPid the department PID to place the agent in
     * @param positionPid   the position PID (nullable)
     */
    void enrollAsEmployee(Long agentId, String departmentPid, String positionPid);

    /**
     * Remove an agent from the org chart.
     *
     * <p>Clears agent_definition.employee_id and deactivates the linked
     * org_employee and service tenant_member.</p>
     *
     * @param agentId the agent definition ID
     */
    void removeFromOrg(Long agentId);

    /**
     * Get the effective tenant member ID for permission evaluation.
     *
     * <p>If the agent has an employee_id (independent mode), returns the
     * linked service member's ID. Otherwise falls back to the triggering
     * user's member ID (proxy mode).</p>
     *
     * @param agentId       the agent definition ID
     * @param triggerUserId the user ID of the human who triggered the agent
     * @return the member ID to use for permission checks
     */
    Long getAgentMemberId(Long agentId, Long triggerUserId);
}
