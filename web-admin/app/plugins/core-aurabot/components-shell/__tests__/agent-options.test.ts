import { describe, expect, it } from 'vitest';

import { normalizeAgentOptions } from '../AuraBotPanel';

describe('normalizeAgentOptions', () => {
  it('accepts Dynamic API agent_definition fields and keeps active agents selectable', () => {
    const options = normalizeAgentOptions([
      {
        agent_code: 'pcba_procurement_comparison_agent',
        name: 'PCBA Procurement Advisor',
        status: 'active',
      },
      {
        agent_code: 'legacy_agent',
        agent_name: 'Legacy Agent',
        agent_status: 'published',
      },
      {
        agent_code: 'draft_agent',
        name: 'Draft Agent',
        status: 'draft',
      },
    ]);

    expect(options).toEqual([
      {
        agent_code: 'pcba_procurement_comparison_agent',
        agent_name: 'PCBA Procurement Advisor',
        agent_status: 'active',
      },
      {
        agent_code: 'legacy_agent',
        agent_name: 'Legacy Agent',
        agent_status: 'published',
      },
    ]);
  });
});
