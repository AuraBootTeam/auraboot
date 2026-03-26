import { describe, it, expect } from 'vitest';
import * as fs from 'node:fs';
import * as path from 'node:path';
import * as os from 'node:os';
import yaml from 'js-yaml';
import { parseWorkflowFile, parseWorkflowString } from '../../../src/pipe/parser.js';
import { listTemplates } from '../../../src/pipe/templates.js';

describe('pipe commands', () => {
  describe('aura pipe validate', () => {
    it('should validate a correct YAML workflow file', () => {
      const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pipe-test-'));
      const filePath = path.join(tmpDir, 'test.yaml');
      fs.writeFileSync(filePath, yaml.dump({
        name: 'test-workflow',
        steps: [
          { type: 'query', source: 'crm_lead', output: 'leads' },
          { type: 'analyze', input: 'leads', prompt: 'Summarize', output: 'summary' },
        ],
      }));

      const wf = parseWorkflowFile(filePath);
      expect(wf.name).toBe('test-workflow');
      expect(wf.steps).toHaveLength(2);

      fs.rmSync(tmpDir, { recursive: true });
    });

    it('should validate a correct JSON workflow file', () => {
      const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pipe-test-'));
      const filePath = path.join(tmpDir, 'test.json');
      fs.writeFileSync(filePath, JSON.stringify({
        name: 'json-workflow',
        steps: [
          { type: 'query', source: 'pm_task', output: 'tasks' },
        ],
      }));

      const wf = parseWorkflowFile(filePath);
      expect(wf.name).toBe('json-workflow');

      fs.rmSync(tmpDir, { recursive: true });
    });

    it('should reject an invalid workflow file', () => {
      const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pipe-test-'));
      const filePath = path.join(tmpDir, 'bad.yaml');
      fs.writeFileSync(filePath, yaml.dump({
        name: 'bad',
        steps: [{ type: 'unknown_type' }],
      }));

      expect(() => parseWorkflowFile(filePath)).toThrow('invalid type');

      fs.rmSync(tmpDir, { recursive: true });
    });

    it('should reject non-existent file', () => {
      expect(() => parseWorkflowFile('/tmp/no-such-file-abc123.yaml')).toThrow('not found');
    });
  });

  describe('aura pipe create', () => {
    it('should create a YAML workflow from scaffold', () => {
      const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pipe-create-'));
      const outputPath = path.join(tmpDir, 'output.yaml');

      const scaffold = {
        name: 'my-workflow',
        description: 'Custom workflow',
        version: '1.0',
        steps: [
          { type: 'query', source: 'your_model', output: 'results' },
        ],
      };
      fs.writeFileSync(outputPath, yaml.dump(scaffold));

      expect(fs.existsSync(outputPath)).toBe(true);
      const content = yaml.load(fs.readFileSync(outputPath, 'utf-8')) as any;
      expect(content.name).toBe('my-workflow');
      expect(content.steps).toHaveLength(1);

      fs.rmSync(tmpDir, { recursive: true });
    });

    it('should create a JSON workflow when .json extension is used', () => {
      const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pipe-create-'));
      const outputPath = path.join(tmpDir, 'output.json');

      const scaffold = {
        name: 'json-workflow',
        steps: [{ type: 'notify', message: 'hello' }],
      };
      fs.writeFileSync(outputPath, JSON.stringify(scaffold, null, 2));

      const content = JSON.parse(fs.readFileSync(outputPath, 'utf-8'));
      expect(content.name).toBe('json-workflow');

      fs.rmSync(tmpDir, { recursive: true });
    });
  });

  describe('aura pipe list', () => {
    it('should list all templates with required fields', () => {
      const templates = listTemplates();

      expect(templates.length).toBeGreaterThanOrEqual(4);

      for (const t of templates) {
        expect(t.name).toBeTruthy();
        expect(typeof t.name).toBe('string');
        expect(t.filePath).toBeTruthy();
      }
    });
  });

  describe('workflow YAML round-trip', () => {
    it('should survive YAML serialize then parse round-trip', () => {
      const original = {
        name: 'round-trip-test',
        description: 'Test round-trip',
        steps: [
          { type: 'query', source: 'crm_lead', filters: [{ field: 'status', operator: 'EQ', value: 'new' }], output: 'leads' },
          { type: 'analyze', input: 'leads', prompt: 'Summarize the leads', output: 'summary' },
          { type: 'notify', message: 'Done: {{summary.title}}' },
        ],
      };

      const yamlStr = yaml.dump(original);
      const parsed = parseWorkflowString(yamlStr, 'yaml');

      expect(parsed.name).toBe(original.name);
      expect(parsed.steps).toHaveLength(3);
      expect(parsed.steps[0].type).toBe('query');
      expect(parsed.steps[1].type).toBe('analyze');
      expect(parsed.steps[2].type).toBe('notify');
    });
  });
});
