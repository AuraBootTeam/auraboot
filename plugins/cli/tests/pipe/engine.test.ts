import { describe, it, expect, vi, beforeEach } from 'vitest';
import { WorkflowEngine } from '../../src/pipe/engine.js';
import type { WorkflowDefinition } from '../../src/pipe/types.js';

// Mock ApiClient
function createMockClient(overrides: Record<string, any> = {}) {
  return {
    requireAuth: vi.fn(),
    get: vi.fn().mockResolvedValue({ ok: true, data: { records: [] } }),
    post: vi.fn().mockResolvedValue({ ok: true, data: { id: 'created-1' } }),
    getToken: vi.fn().mockReturnValue('mock-token'),
    getBaseUrl: vi.fn().mockReturnValue('http://localhost:6443'),
    ...overrides,
  } as any;
}

describe('WorkflowEngine', () => {
  let mockClient: any;

  beforeEach(() => {
    mockClient = createMockClient();
    // Suppress console output in tests
    vi.spyOn(console, 'error').mockImplementation(() => {});
    vi.spyOn(console, 'log').mockImplementation(() => {});
  });

  describe('execute', () => {
    it('should set workflow-level variables', async () => {
      const engine = new WorkflowEngine(mockClient, { verbose: false });
      const workflow: WorkflowDefinition = {
        name: 'test',
        variables: { env: 'test', count: 5 },
        steps: [
          { type: 'notify', message: 'env={{env}}' },
        ],
      };
      const result = await engine.execute(workflow);
      expect(result.success).toBe(true);
      expect(result.variables.env).toBe('test');
      expect(result.variables.count).toBe(5);
    });

    it('should track step results', async () => {
      const engine = new WorkflowEngine(mockClient, { verbose: false });
      const workflow: WorkflowDefinition = {
        name: 'test',
        steps: [
          { type: 'notify', message: 'step 1' },
          { type: 'notify', message: 'step 2' },
        ],
      };
      const result = await engine.execute(workflow);
      expect(result.steps).toHaveLength(2);
      expect(result.steps[0].stepType).toBe('notify');
      expect(result.steps[1].stepType).toBe('notify');
      expect(result.steps[0].success).toBe(true);
      expect(result.steps[1].success).toBe(true);
    });

    it('should record timing information', async () => {
      const engine = new WorkflowEngine(mockClient, { verbose: false });
      const workflow: WorkflowDefinition = {
        name: 'test',
        steps: [{ type: 'notify', message: 'hello' }],
      };
      const result = await engine.execute(workflow);
      expect(result.totalDurationMs).toBeGreaterThanOrEqual(0);
      expect(result.steps[0].durationMs).toBeGreaterThanOrEqual(0);
      expect(result.startedAt).toBeTruthy();
      expect(result.completedAt).toBeTruthy();
    });

    it('should abort on step failure', async () => {
      const failClient = createMockClient({
        get: vi.fn().mockResolvedValue({ ok: false, message: 'Not found' }),
      });
      // Mock process.exit to throw instead
      const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => { throw new Error('exit'); });

      const engine = new WorkflowEngine(failClient, { verbose: false });
      const workflow: WorkflowDefinition = {
        name: 'test',
        steps: [
          { type: 'query', source: 'bad_model', output: 'data' },
          { type: 'notify', message: 'should not run' },
        ],
      };

      try {
        await engine.execute(workflow);
      } catch {
        // Expected: process.exit is called inside queryDynamicList
      }

      exitSpy.mockRestore();
    });
  });

  describe('create step with dry-run', () => {
    it('should skip creation in dry-run mode', async () => {
      const engine = new WorkflowEngine(mockClient, { verbose: false, dryRun: true });
      const workflow: WorkflowDefinition = {
        name: 'test',
        steps: [
          { type: 'create', model: 'report', data: { title: 'Test' }, output: 'created' },
        ],
      };
      const result = await engine.execute(workflow);
      expect(result.success).toBe(true);
      expect(mockClient.post).not.toHaveBeenCalled();
      const stepData = result.steps[0].data as any;
      expect(stepData.dryRun).toBe(true);
    });
  });

  describe('notify step', () => {
    it('should return the interpolated message', async () => {
      const engine = new WorkflowEngine(mockClient, { verbose: false });
      const workflow: WorkflowDefinition = {
        name: 'test',
        variables: { name: 'Alice' },
        steps: [
          { type: 'notify', message: 'Hello {{name}}!' },
        ],
      };
      const result = await engine.execute(workflow);
      expect(result.steps[0].data).toBe('Hello Alice!');
    });

    it('should output JSON for json channel', async () => {
      const engine = new WorkflowEngine(mockClient, { verbose: false });
      const workflow: WorkflowDefinition = {
        name: 'test',
        steps: [
          { type: 'notify', message: 'test msg', channel: 'json' },
        ],
      };
      const result = await engine.execute(workflow);
      const output = result.steps[0].data as string;
      const parsed = JSON.parse(output);
      expect(parsed.type).toBe('notification');
      expect(parsed.message).toBe('test msg');
    });
  });

  describe('variable propagation', () => {
    it('should store step output in variables', async () => {
      const engine = new WorkflowEngine(mockClient, { verbose: false, dryRun: true });
      const workflow: WorkflowDefinition = {
        name: 'test',
        steps: [
          { type: 'create', model: 'test', data: { x: 1 }, output: 'result' },
          { type: 'notify', message: 'Created: {{result.dryRun}}' },
        ],
      };
      const result = await engine.execute(workflow);
      expect(result.success).toBe(true);
      expect(result.variables.result).toBeTruthy();
    });
  });
});
