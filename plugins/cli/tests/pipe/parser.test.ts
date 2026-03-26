import { describe, it, expect } from 'vitest';
import { validateWorkflow, parseWorkflowString } from '../../src/pipe/parser.js';

describe('workflow parser', () => {
  describe('validateWorkflow', () => {
    it('should accept a valid minimal workflow', () => {
      const wf = validateWorkflow({
        name: 'test-workflow',
        steps: [
          { type: 'query', source: 'crm_lead', output: 'leads' },
        ],
      });
      expect(wf.name).toBe('test-workflow');
      expect(wf.steps).toHaveLength(1);
    });

    it('should accept a workflow with all step types', () => {
      const wf = validateWorkflow({
        name: 'full-workflow',
        description: 'Full pipeline test',
        version: '1.0',
        variables: { env: 'test' },
        steps: [
          { type: 'query', source: 'crm_lead', output: 'leads', filters: [{ field: 'status', operator: 'EQ', value: 'new' }] },
          { type: 'analyze', input: 'leads', prompt: 'Summarize', output: 'summary' },
          { type: 'create', model: 'daily_report', data: { title: 'Report' } },
          { type: 'notify', message: 'Done' },
        ],
      });
      expect(wf.steps).toHaveLength(4);
      expect(wf.variables).toEqual({ env: 'test' });
    });

    it('should accept query with nq (NamedQuery)', () => {
      const wf = validateWorkflow({
        name: 'nq-workflow',
        steps: [
          { type: 'query', nq: 'crm_dashboard_kpi', output: 'kpi' },
        ],
      });
      expect(wf.steps[0].type).toBe('query');
    });

    it('should reject null input', () => {
      expect(() => validateWorkflow(null)).toThrow('non-null object');
    });

    it('should reject missing name', () => {
      expect(() => validateWorkflow({ steps: [{ type: 'query', source: 'x', output: 'y' }] }))
        .toThrow('"name" field');
    });

    it('should reject missing steps', () => {
      expect(() => validateWorkflow({ name: 'test' })).toThrow('"steps" array');
    });

    it('should reject empty steps array', () => {
      expect(() => validateWorkflow({ name: 'test', steps: [] })).toThrow('"steps" array');
    });

    it('should reject unknown step type', () => {
      expect(() => validateWorkflow({
        name: 'test',
        steps: [{ type: 'unknown' }],
      })).toThrow('invalid type "unknown"');
    });

    it('should reject query without source or nq', () => {
      expect(() => validateWorkflow({
        name: 'test',
        steps: [{ type: 'query', output: 'data' }],
      })).toThrow('"source" or "nq"');
    });

    it('should reject query without output', () => {
      expect(() => validateWorkflow({
        name: 'test',
        steps: [{ type: 'query', source: 'crm_lead' }],
      })).toThrow('"output" field');
    });

    it('should reject analyze with undefined input variable', () => {
      expect(() => validateWorkflow({
        name: 'test',
        steps: [
          { type: 'analyze', input: 'unknown_var', prompt: 'test', output: 'result' },
        ],
      })).toThrow('not defined by a preceding step');
    });

    it('should accept analyze when input comes from a prior step', () => {
      const wf = validateWorkflow({
        name: 'test',
        steps: [
          { type: 'query', source: 'crm_lead', output: 'leads' },
          { type: 'analyze', input: 'leads', prompt: 'Summarize', output: 'result' },
        ],
      });
      expect(wf.steps).toHaveLength(2);
    });

    it('should accept analyze when input comes from workflow variables', () => {
      const wf = validateWorkflow({
        name: 'test',
        variables: { preset_data: 'some value' },
        steps: [
          { type: 'analyze', input: 'preset_data', prompt: 'Summarize', output: 'result' },
        ],
      });
      expect(wf.steps).toHaveLength(1);
    });

    it('should reject analyze without prompt', () => {
      expect(() => validateWorkflow({
        name: 'test',
        steps: [
          { type: 'query', source: 'x', output: 'data' },
          { type: 'analyze', input: 'data', output: 'result' },
        ],
      })).toThrow('"prompt" field');
    });

    it('should reject create without model', () => {
      expect(() => validateWorkflow({
        name: 'test',
        steps: [{ type: 'create', data: { x: 1 } }],
      })).toThrow('"model" field');
    });

    it('should reject create without data', () => {
      expect(() => validateWorkflow({
        name: 'test',
        steps: [{ type: 'create', model: 'report' }],
      })).toThrow('"data" field');
    });

    it('should reject notify without message', () => {
      expect(() => validateWorkflow({
        name: 'test',
        steps: [{ type: 'notify' }],
      })).toThrow('"message" field');
    });
  });

  describe('parseWorkflowString', () => {
    it('should parse YAML workflow string', () => {
      const yamlStr = `
name: yaml-test
steps:
  - type: query
    source: crm_lead
    output: leads
`;
      const wf = parseWorkflowString(yamlStr, 'yaml');
      expect(wf.name).toBe('yaml-test');
      expect(wf.steps).toHaveLength(1);
    });

    it('should parse JSON workflow string', () => {
      const jsonStr = JSON.stringify({
        name: 'json-test',
        steps: [{ type: 'query', source: 'crm_lead', output: 'leads' }],
      });
      const wf = parseWorkflowString(jsonStr, 'json');
      expect(wf.name).toBe('json-test');
    });
  });
});
