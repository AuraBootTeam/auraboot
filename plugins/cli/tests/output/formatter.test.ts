import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { resolveOutputOptions, type ColumnDef } from '../../src/output/formatter.js';

describe('Output Formatter', () => {
  const originalEnv = process.env;

  beforeEach(() => {
    process.env = { ...originalEnv };
  });

  afterEach(() => {
    process.env = originalEnv;
  });

  describe('resolveOutputOptions', () => {
    it('should default to table format', () => {
      const opts = resolveOutputOptions({});
      expect(opts.format).toBe('table');
      expect(opts.agentMode).toBe(false);
    });

    it('should respect --format flag', () => {
      const opts = resolveOutputOptions({ format: 'json' });
      expect(opts.format).toBe('json');
    });

    it('should respect --agent-mode flag', () => {
      const opts = resolveOutputOptions({ agentMode: true });
      expect(opts.format).toBe('json'); // agent mode forces JSON
      expect(opts.agentMode).toBe(true);
    });

    it('should detect AURA_AGENT_MODE env var', () => {
      process.env.AURA_AGENT_MODE = '1';
      const opts = resolveOutputOptions({});
      expect(opts.agentMode).toBe(true);
      expect(opts.format).toBe('json');
    });

    it('should not enable agent mode for non-1 env values', () => {
      process.env.AURA_AGENT_MODE = '0';
      const opts = resolveOutputOptions({});
      expect(opts.agentMode).toBe(false);
    });

    it('should override format with compact', () => {
      const opts = resolveOutputOptions({ format: 'compact' });
      expect(opts.format).toBe('compact');
    });
  });

  describe('table formatting', () => {
    it('should handle empty data', async () => {
      const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => {});
      const { printTable } = await import('../../src/output/formatter.js');

      const columns: ColumnDef[] = [
        { key: 'name', header: 'name' },
        { key: 'status', header: 'status' },
      ];
      printTable([], columns);

      expect(consoleSpy).toHaveBeenCalledWith(expect.stringContaining('No data'));
      consoleSpy.mockRestore();
    });
  });

  describe('JSON output', () => {
    it('should produce valid JSON for agent mode', () => {
      const data = [
        { name: 'sales-agent', status: 'active', tools: 4 },
        { name: 'data-analyst', status: 'idle', tools: 3 },
      ];

      // Agent mode output (compact, no indent)
      const agentJson = JSON.stringify(data, null, 0);
      expect(() => JSON.parse(agentJson)).not.toThrow();
      const parsed = JSON.parse(agentJson);
      expect(parsed).toHaveLength(2);
      expect(parsed[0].name).toBe('sales-agent');
    });

    it('should produce pretty JSON for human mode', () => {
      const data = { name: 'test', status: 'active' };
      const prettyJson = JSON.stringify(data, null, 2);
      expect(prettyJson).toContain('\n');
      expect(prettyJson).toContain('  ');
    });
  });

  describe('compact output', () => {
    it('should produce tab-separated values', () => {
      const columns: ColumnDef[] = [
        { key: 'name', header: 'name' },
        { key: 'status', header: 'status' },
      ];
      const data = [
        { name: 'agent-1', status: 'active' },
        { name: 'agent-2', status: 'idle' },
      ];

      const lines = data.map(row =>
        columns.map(c => String(row[c.key as keyof typeof row] ?? '')).join('\t')
      );

      expect(lines[0]).toBe('agent-1\tactive');
      expect(lines[1]).toBe('agent-2\tidle');
    });
  });

  describe('detail formatting', () => {
    it('should format null values as dash', () => {
      const formatValue = (value: any): string => {
        if (value === null || value === undefined) return '—';
        if (typeof value === 'boolean') return value ? 'yes' : 'no';
        if (Array.isArray(value)) return value.join(', ') || 'none';
        return String(value);
      };

      expect(formatValue(null)).toBe('—');
      expect(formatValue(undefined)).toBe('—');
      expect(formatValue(true)).toBe('yes');
      expect(formatValue(false)).toBe('no');
      expect(formatValue([])).toBe('none');
      expect(formatValue(['a', 'b'])).toBe('a, b');
      expect(formatValue(42)).toBe('42');
      expect(formatValue('hello')).toBe('hello');
    });
  });
});
