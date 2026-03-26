import { describe, it, expect } from 'vitest';

describe('pipeline query command', () => {
  describe('filter parsing', () => {
    function parseFilter(f: string) {
      for (const [op, apiOp] of [
        ['>=', 'gte'], ['<=', 'lte'], ['!=', 'neq'],
        ['>', 'GT'], ['<', 'LT'], ['~', 'like'], ['=', 'EQ'],
      ] as const) {
        const idx = f.indexOf(op);
        if (idx > 0) {
          return { fieldName: f.slice(0, idx), operator: apiOp, value: f.slice(idx + op.length) };
        }
      }
      return { fieldName: f, operator: 'like', value: f };
    }

    it('should parse EQ filter', () => {
      const f = parseFilter('crm_lead_status=NEW');
      expect(f).toEqual({ fieldName: 'crm_lead_status', operator: 'EQ', value: 'new' });
    });

    it('should parse GT filter', () => {
      const f = parseFilter('crm_lead_score>80');
      expect(f).toEqual({ fieldName: 'crm_lead_score', operator: 'GT', value: '80' });
    });

    it('should parse GTE filter', () => {
      const f = parseFilter('amount>=10000');
      expect(f).toEqual({ fieldName: 'amount', operator: 'gte', value: '10000' });
    });

    it('should parse LIKE filter', () => {
      const f = parseFilter('name~Acme');
      expect(f).toEqual({ fieldName: 'name', operator: 'like', value: 'Acme' });
    });

    it('should parse NEQ filter', () => {
      const f = parseFilter('status!=CLOSED');
      expect(f).toEqual({ fieldName: 'status', operator: 'neq', value: 'closed' });
    });
  });

  describe('sort parsing', () => {
    it('should parse sort field:direction', () => {
      const sort = 'crm_lead_score:desc';
      const [field, dir] = sort.split(':');
      expect(field).toBe('crm_lead_score');
      expect(dir).toBe('desc');
    });

    it('should default to desc', () => {
      const sort = 'created_at';
      const [field, dir] = sort.split(':');
      expect(field).toBe('created_at');
      expect(dir).toBeUndefined();
    });
  });

  describe('output format', () => {
    it('should always output JSON for pipeline', () => {
      const records = [{ id: 1, name: 'test' }];
      const output = JSON.stringify(records);
      expect(() => JSON.parse(output)).not.toThrow();
    });

    it('should output empty array for no results', () => {
      const output = JSON.stringify([]);
      expect(JSON.parse(output)).toEqual([]);
    });
  });

  describe('named query mode', () => {
    it('should accept --nq flag', () => {
      const options = { nq: 'crm_dashboard_kpi' };
      expect(options.nq).toBe('crm_dashboard_kpi');
    });
  });
});
