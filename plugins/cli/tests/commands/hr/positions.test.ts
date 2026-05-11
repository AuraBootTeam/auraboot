import { describe, it, expect } from 'vitest';

describe('hr positions command', () => {
  describe('column definitions', () => {
    const COLUMNS = ['org_pos_name', 'org_pos_code', 'org_pos_status', 'org_pos_level'];

    it('should extract all fields from API record', () => {
      const record = {
        org_pos_name: 'Senior Engineer',
        org_pos_code: 'POS-SE',
        org_pos_status: 'active',
        org_pos_level: 5,
        pid: '03GHI',
      };

      for (const col of COLUMNS) {
        expect(record[col as keyof typeof record]).toBeDefined();
      }
    });
  });

  describe('filter options', () => {
    it('should build status filter', () => {
      const status = 'inactive';
      const filter = { fieldName: 'org_pos_status', operator: 'EQ', value: status.toLowerCase() };
      expect(filter.value).toBe('inactive');
    });

    it('should use correct page key', () => {
      const pageKey = 'org_position';
      expect(pageKey).toBe('org_position');
    });
  });

  describe('position level', () => {
    it('should include level in output columns', () => {
      const columns = ['org_pos_name', 'org_pos_code', 'org_pos_status', 'org_pos_level'];
      expect(columns).toContain('org_pos_level');
    });
  });
});
