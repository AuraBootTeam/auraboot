export const MISSION_CONTROL_DSL_PATHS = {
  taskList: '/p/agent_task',
  approvalList: '/p/agent_approval',
  memoryList: '/p/agent_memory',
  agentList: '/p/agent_definition',
  agentDetail: (pid: string) => `/p/agent_definition/view/${pid}`,
  scheduleList: '/p/agent_schedule',
  artifactList: '/p/agent_artifact',
  approvalPolicyList: '/p/approval_policy',
} as const;
