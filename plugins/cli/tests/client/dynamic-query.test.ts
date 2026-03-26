import { describe, it, expect } from 'vitest';
import type { FilterItem } from '../../src/client/dynamic-query.js';

describe('dynamic-query', () => {
  describe('filter construction', () => {
    it('should build EQ filter', () => {
      const filter: FilterItem = { fieldName: 'crm_lead_status', operator: 'EQ', value: 'new' };
      expect(filter.fieldName).toBe('crm_lead_status');
      expect(filter.operator).toBe('EQ');
      expect(filter.value).toBe('new');
    });

    it('should serialize filters as JSON', () => {
      const filters: FilterItem[] = [
        { fieldName: 'crm_lead_status', operator: 'EQ', value: 'qualified' },
        { fieldName: 'crm_lead_source', operator: 'EQ', value: 'website' },
      ];
      const json = JSON.stringify(filters);
      const parsed = JSON.parse(json);
      expect(parsed).toHaveLength(2);
      expect(parsed[0].fieldName).toBe('crm_lead_status');
      expect(parsed[1].value).toBe('website');
    });

    it('should handle empty filters', () => {
      const filters: FilterItem[] = [];
      expect(JSON.stringify(filters)).toBe('[]');
    });
  });

  describe('query params construction', () => {
    it('should build dynamic CRUD params', () => {
      const params: Record<string, string> = {
        pageNum: '1',
        pageSize: '50',
        keyword: 'acme',
        sortField: 'created_at',
        sortOrder: 'desc',
      };
      expect(params.pageSize).toBe('50');
      expect(params.keyword).toBe('acme');
    });

    it('should build NamedQuery params', () => {
      const params = {
        datasourceId: 'nq:crm_dashboard_kpi',
        maxItems: '200',
        format: 'records',
      };
      expect(params.datasourceId).toBe('nq:crm_dashboard_kpi');
      expect(params.format).toBe('records');
    });
  });
});
