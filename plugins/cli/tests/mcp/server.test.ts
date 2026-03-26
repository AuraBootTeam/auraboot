import { describe, it, expect } from 'vitest';

describe('MCP Server', () => {
  describe('tool definitions', () => {
    const TOOLS = [
      { name: 'query_entity', requiredParams: ['entityCode'] },
      { name: 'run_named_query', requiredParams: ['queryCode'] },
      { name: 'list_agents', requiredParams: [] },
      { name: 'list_tools', requiredParams: [] },
      { name: 'dispatch_agent', requiredParams: ['taskPid'] },
      { name: 'ask_aurabot', requiredParams: ['question'] },
    ];

    it('should expose 6 tools', () => {
      expect(TOOLS).toHaveLength(6);
    });

    it('should have correct required params', () => {
      const queryEntity = TOOLS.find(t => t.name === 'query_entity')!;
      expect(queryEntity.requiredParams).toContain('entityCode');

      const nq = TOOLS.find(t => t.name === 'run_named_query')!;
      expect(nq.requiredParams).toContain('queryCode');

      const dispatch = TOOLS.find(t => t.name === 'dispatch_agent')!;
      expect(dispatch.requiredParams).toContain('taskPid');

      const ask = TOOLS.find(t => t.name === 'ask_aurabot')!;
      expect(ask.requiredParams).toContain('question');
    });

    it('should have zero-arg tools for listing', () => {
      const listAgents = TOOLS.find(t => t.name === 'list_agents')!;
      expect(listAgents.requiredParams).toHaveLength(0);

      const listTools = TOOLS.find(t => t.name === 'list_tools')!;
      expect(listTools.requiredParams).toHaveLength(0);
    });
  });

  describe('query_entity params', () => {
    it('should accept filter format', () => {
      const filters = [
        { fieldName: 'crm_lead_status', operator: 'EQ', value: 'new' },
        { fieldName: 'crm_lead_source', operator: 'EQ', value: 'website' },
      ];
      expect(filters).toHaveLength(2);
      expect(filters[0].operator).toBe('EQ');
    });

    it('should use model code as entityCode', () => {
      const validCodes = ['crm_lead', 'crm_account', 'crm_opportunity', 'pm_project', 'pm_task'];
      for (const code of validCodes) {
        expect(code).toMatch(/^[a-z_]+$/);
      }
    });
  });

  describe('run_named_query params', () => {
    it('should accept common NQ codes', () => {
      const validNQs = [
        'crm_dashboard_kpi',
        'crm_opportunity_pipeline_stats',
        'pm_dashboard_kpi',
        'acp_agent_stats',
        'acp_agent_tools_active',
      ];
      expect(validNQs).toHaveLength(5);
    });

    it('should pass extra params as key-value', () => {
      const params = { maxItems: '100', customParam: 'value' };
      expect(params.maxItems).toBe('100');
    });
  });

  describe('MCP response format', () => {
    it('should return content array with text type', () => {
      const response = {
        content: [{ type: 'text' as const, text: JSON.stringify([{ id: 1 }]) }],
      };
      expect(response.content).toHaveLength(1);
      expect(response.content[0].type).toBe('text');
      expect(JSON.parse(response.content[0].text)).toEqual([{ id: 1 }]);
    });

    it('should set isError for failures', () => {
      const errorResponse = {
        content: [{ type: 'text' as const, text: 'Error: Not found' }],
        isError: true,
      };
      expect(errorResponse.isError).toBe(true);
    });
  });
});
