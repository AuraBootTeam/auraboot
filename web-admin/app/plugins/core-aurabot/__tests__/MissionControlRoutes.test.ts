import { describe, expect, it } from 'vitest';

import { MISSION_CONTROL_DSL_PATHS } from '../pages/mission-control/routes';

describe('Mission Control DSL links', () => {
  it('uses model_code URL segments that resolve to imported DSL page schemas', () => {
    expect(MISSION_CONTROL_DSL_PATHS).toMatchObject({
      taskList: '/p/agent_task',
      approvalList: '/p/agent_approval',
      memoryList: '/p/agent_memory',
      agentList: '/p/agent_definition',
      scheduleList: '/p/agent_schedule',
      artifactList: '/p/agent_artifact',
      approvalPolicyList: '/p/approval_policy',
    });
    expect(MISSION_CONTROL_DSL_PATHS.agentDetail('AGENT001')).toBe(
      '/p/agent_definition/view/AGENT001',
    );
  });
});
