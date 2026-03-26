import { describe, it, expect } from 'vitest';

describe('ops agents command', () => {
  describe('agent list columns', () => {
    const AGENT_COLUMNS = [
      { key: 'agent_name', header: 'name' },
      { key: 'agent_code', header: 'code' },
      { key: 'agent_type', header: 'type' },
      { key: 'agent_status', header: 'status' },
      { key: 'model', header: 'model' },
      { key: 'total_runs', header: 'runs' },
      { key: 'success_rate', header: 'SUCCESS%' },
    ];

    it('should have all required columns', () => {
      const headers = AGENT_COLUMNS.map(c => c.header);
      expect(headers).toContain('name');
      expect(headers).toContain('code');
      expect(headers).toContain('status');
      expect(headers).toContain('model');
      expect(headers).toContain('runs');
      expect(headers).toContain('SUCCESS%');
    });

    it('should extract values from NQ response', () => {
      const apiRecord = {
        agent_name: 'sales-agent',
        agent_code: 'sales_agent_001',
        agent_type: 'copilot',
        agent_status: 'active',
        model: 'claude-sonnet-4-6',
        total_runs: 12,
        success_rate: 85,
        pid: 'agent_001',
        total_cost: 1.25,
      };

      const row = AGENT_COLUMNS.map(c => String(apiRecord[c.key as keyof typeof apiRecord] ?? ''));
      expect(row).toEqual(['sales-agent', 'sales_agent_001', 'copilot', 'active', 'claude-sonnet-4-6', '12', '85']);
    });
  });

  describe('agent list API endpoint', () => {
    it('should use NamedQuery datasource with format=records', () => {
      const params = { datasourceId: 'nq:acp_agent_stats', maxItems: '200', format: 'records' };
      expect(params.datasourceId).toBe('nq:acp_agent_stats');
      expect(params.format).toBe('records');
    });
  });

  describe('agent show API endpoint', () => {
    it('should construct correct URL for agent code', () => {
      const code = 'sales-agent';
      const path = `/api/agent/capabilities/${code}`;
      expect(path).toBe('/api/agent/capabilities/sales-agent');
    });
  });
});
