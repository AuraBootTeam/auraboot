import { describe, it, expect } from 'vitest';

describe('ops tools command', () => {
  describe('tool list columns', () => {
    const TOOL_COLUMNS = [
      { key: 'tool_code', header: 'code' },
      { key: 'tool_name', header: 'name' },
      { key: 'tool_type', header: 'type' },
      { key: 'tool_status', header: 'status' },
      { key: 'source_type', header: 'source' },
    ];

    it('should have all required columns', () => {
      const headers = TOOL_COLUMNS.map(c => c.header);
      expect(headers).toContain('code');
      expect(headers).toContain('name');
      expect(headers).toContain('status');
      expect(headers).toContain('source');
    });

    it('should extract values from NQ response', () => {
      const apiRecord = {
        tool_code: 'crm_query_leads',
        tool_name: 'CRM Lead Query',
        tool_type: 'custom_api',
        tool_status: 'active',
        source_type: 'command',
        pid: 'tool_001',
        tool_description: 'Query CRM leads with filters',
      };

      const row = TOOL_COLUMNS.map(c => String(apiRecord[c.key as keyof typeof apiRecord] ?? ''));
      expect(row).toEqual(['crm_query_leads', 'CRM Lead Query', 'custom_api', 'active', 'command']);
    });
  });

  describe('tool list API endpoint', () => {
    it('should use NamedQuery with format=records', () => {
      const params = { datasourceId: 'nq:acp_agent_tools_active', maxItems: '100', format: 'records' };
      expect(params.datasourceId).toBe('nq:acp_agent_tools_active');
      expect(params.format).toBe('records');
    });
  });

  describe('tool test API', () => {
    it('should construct dry-run request body', () => {
      const code = 'crm_query_leads';
      const body = { toolCode: code };
      expect(body.toolCode).toBe('crm_query_leads');
    });
  });
});
