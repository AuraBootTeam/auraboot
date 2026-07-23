import { describe, expect, it } from 'vitest';
import { toAgentErrorReport } from '../../src/validation/agent-report.js';
import { addMessage, createResult } from '../../src/validation/types.js';

describe('toAgentErrorReport', () => {
  it('reports ok:true with empty errors when there are no error messages', () => {
    const result = createResult();
    addMessage(result, {
      code: 'G-INFO',
      category: 'governance',
      severity: 'info',
      message: 'just fyi',
    });
    const report = toAgentErrorReport(result);
    expect(report.ok).toBe(true);
    expect(report.errorCount).toBe(0);
    expect(report.errors).toEqual([]);
  });

  it('aggregates errors with path/code/message and passes through expected + agentInstruction', () => {
    const result = createResult();
    addMessage(result, {
      code: 'S-UNKNOWN-PROP',
      category: 'semantic',
      severity: 'error',
      message: 'Unknown property "displayFiled"',
      path: '/pages/0/displayFiled',
      expected: 'displayField',
      agentInstruction: 'Rename displayFiled to displayField',
    });

    const report = toAgentErrorReport(result);

    expect(report.ok).toBe(false);
    expect(report.errorCount).toBe(1);
    expect(report.errors).toEqual([
      {
        code: 'S-UNKNOWN-PROP',
        message: 'Unknown property "displayFiled"',
        path: '/pages/0/displayFiled',
        expected: 'displayField',
        agentInstruction: 'Rename displayFiled to displayField',
      },
    ]);
  });

  it('omits optional fields that were not provided (clean JSON for the agent)', () => {
    const result = createResult();
    addMessage(result, {
      code: 'S-REF',
      category: 'semantic',
      severity: 'error',
      message: 'dangling reference',
    });
    const [entry] = toAgentErrorReport(result).errors;
    expect(entry).toEqual({ code: 'S-REF', message: 'dangling reference' });
    expect('expected' in entry).toBe(false);
    expect('agentInstruction' in entry).toBe(false);
    expect('path' in entry).toBe(false);
  });

  it('separates warnings from errors', () => {
    const result = createResult();
    addMessage(result, { code: 'E1', category: 'semantic', severity: 'error', message: 'boom' });
    addMessage(result, { code: 'W1', category: 'governance', severity: 'warning', message: 'meh' });
    const report = toAgentErrorReport(result);
    expect(report.errorCount).toBe(1);
    expect(report.warningCount).toBe(1);
    expect(report.errors.map((e) => e.code)).toEqual(['E1']);
    expect(report.warnings.map((e) => e.code)).toEqual(['W1']);
  });
});
