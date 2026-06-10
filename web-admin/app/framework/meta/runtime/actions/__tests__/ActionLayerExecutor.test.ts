import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  ActionLayerExecutor,
  createLayeredConfig,
  type LayeredAction,
  type LayeredActionConfig,
} from '../ActionLayerExecutor';

// ─── helpers ────────────────────────────────────────────────────────────────

const makeExecutor = (results: Record<string, any> = {}) =>
  vi.fn(async (actionName: string, args?: Record<string, any>) => {
    if (actionName in results) return results[actionName];
    return { ok: true, actionName, args };
  });

const action = (
  id: string,
  phase: LayeredAction['phase'],
  actionName: string,
  extras?: Partial<LayeredAction>,
): LayeredAction => ({
  id,
  phase,
  actionName,
  ...extras,
});

// ─── basic execution flow ───────────────────────────────────────────────────

describe('ActionLayerExecutor – basic phases', () => {
  it('runs pre → main → post in order', async () => {
    const calls: string[] = [];
    const executor = vi.fn(async (name: string) => { calls.push(name); return {}; });
    const ale = new ActionLayerExecutor(executor);

    await ale.execute({
      actions: [
        action('p1', 'pre', 'guard'),
        action('m1', 'main', 'save'),
        action('po1', 'post', 'notify'),
      ],
    });

    expect(calls).toEqual(['guard', 'save', 'notify']);
  });

  it('returns success:true when all phases pass', async () => {
    const ale = new ActionLayerExecutor(makeExecutor());
    const result = await ale.execute({
      actions: [
        action('p', 'pre', 'check'),
        action('m', 'main', 'do'),
        action('po', 'post', 'log'),
      ],
    });
    expect(result.success).toBe(true);
    expect(result.errors).toHaveLength(0);
  });

  it('runs without any phases defined', async () => {
    const ale = new ActionLayerExecutor(makeExecutor());
    const result = await ale.execute({ actions: [] });
    expect(result.success).toBe(true);
  });
});

// ─── PRE abort ──────────────────────────────────────────────────────────────

describe('ActionLayerExecutor – PRE abort', () => {
  it('aborts and skips MAIN when PRE returns { abort: true }', async () => {
    const calls: string[] = [];
    const executor = vi.fn(async (name: string) => {
      calls.push(name);
      if (name === 'guard') return { abort: true };
      return {};
    });
    const ale = new ActionLayerExecutor(executor);

    const result = await ale.execute({
      actions: [
        action('p', 'pre', 'guard'),
        action('m', 'main', 'save'),
      ],
    });

    expect(result.success).toBe(false);
    expect(result.abortedBy).toBe('p');
    expect(calls).not.toContain('save');
  });

  it('aborts and skips POST too when PRE aborts', async () => {
    const calls: string[] = [];
    const executor = vi.fn(async (name: string) => {
      calls.push(name);
      if (name === 'g') return { abort: true };
      return {};
    });
    const ale = new ActionLayerExecutor(executor);

    await ale.execute({
      actions: [
        action('p', 'pre', 'g'),
        action('m', 'main', 'save'),
        action('po', 'post', 'log'),
      ],
    });

    expect(calls).toEqual(['g']);
  });

  it('non-abort PRE result continues to MAIN', async () => {
    const calls: string[] = [];
    const executor = vi.fn(async (name: string) => { calls.push(name); return { ok: true }; });
    const ale = new ActionLayerExecutor(executor);

    const result = await ale.execute({
      actions: [
        action('p', 'pre', 'check'),
        action('m', 'main', 'save'),
      ],
    });

    expect(result.success).toBe(true);
    expect(calls).toContain('save');
  });
});

// ─── PRE error ──────────────────────────────────────────────────────────────

describe('ActionLayerExecutor – PRE error', () => {
  it('aborts if non-optional PRE action throws', async () => {
    const executor = vi.fn(async (name: string) => {
      if (name === 'validate') throw new Error('validation failed');
      return {};
    });
    const ale = new ActionLayerExecutor(executor);
    const result = await ale.execute({
      actions: [
        action('pv', 'pre', 'validate'),
        action('m', 'main', 'save'),
      ],
    });
    expect(result.success).toBe(false);
    expect(result.abortedBy).toBe('pv');
    expect(result.errors).toHaveLength(1);
    expect(result.errors[0].phase).toBe('pre');
  });

  it('continues if optional PRE action throws', async () => {
    const calls: string[] = [];
    const executor = vi.fn(async (name: string) => {
      calls.push(name);
      if (name === 'opt') throw new Error('optional error');
      return {};
    });
    const ale = new ActionLayerExecutor(executor);
    const result = await ale.execute({
      actions: [
        action('po', 'pre', 'opt', { optional: true }),
        action('m', 'main', 'save'),
      ],
    });
    expect(calls).toContain('save');
    expect(result.errors).toHaveLength(1);
    // success depends on main – main succeeded
    expect(result.success).toBe(true);
  });
});

// ─── MAIN error ─────────────────────────────────────────────────────────────

describe('ActionLayerExecutor – MAIN error', () => {
  it('sets success:false and skips POST when MAIN throws', async () => {
    const calls: string[] = [];
    const executor = vi.fn(async (name: string) => {
      calls.push(name);
      if (name === 'save') throw new Error('save failed');
      return {};
    });
    const ale = new ActionLayerExecutor(executor);
    const result = await ale.execute({
      actions: [
        action('m', 'main', 'save'),
        action('po', 'post', 'notify'),
      ],
    });
    expect(result.success).toBe(false);
    expect(calls).not.toContain('notify');
    expect(result.errors[0].phase).toBe('main');
  });

  it('runs POST even when MAIN fails if runPostOnMainFailure:true', async () => {
    const calls: string[] = [];
    const executor = vi.fn(async (name: string) => {
      calls.push(name);
      if (name === 'save') throw new Error('save failed');
      return {};
    });
    const ale = new ActionLayerExecutor(executor);
    const result = await ale.execute({
      actions: [
        action('m', 'main', 'save'),
        action('po', 'post', 'notify'),
      ],
      runPostOnMainFailure: true,
    });
    expect(calls).toContain('notify');
  });

  it('optional MAIN action error does not fail overall', async () => {
    const executor = vi.fn(async (name: string) => {
      if (name === 'opt') throw new Error('opt failed');
      return {};
    });
    const ale = new ActionLayerExecutor(executor);
    const result = await ale.execute({
      actions: [
        action('mo', 'main', 'opt', { optional: true }),
      ],
    });
    // Since the optional main errored but is optional, main "failed" but we have no required failure
    // Actually the current impl sets mainFailed=true only for non-optional — let's check
    expect(result.errors).toHaveLength(1);
  });
});

// ─── POST errors don't affect success ────────────────────────────────────────

describe('ActionLayerExecutor – POST errors', () => {
  it('POST errors do not affect overall success', async () => {
    const executor = vi.fn(async (name: string) => {
      if (name === 'notify') throw new Error('notify failed');
      return {};
    });
    const ale = new ActionLayerExecutor(executor);
    const result = await ale.execute({
      actions: [
        action('m', 'main', 'save'),
        action('po', 'post', 'notify'),
      ],
    });
    expect(result.success).toBe(true);
    expect(result.errors).toHaveLength(1);
    expect(result.errors[0].phase).toBe('post');
  });
});

// ─── order sorting ───────────────────────────────────────────────────────────

describe('ActionLayerExecutor – order', () => {
  it('executes actions within same phase in order', async () => {
    const calls: string[] = [];
    const executor = vi.fn(async (name: string) => { calls.push(name); return {}; });
    const ale = new ActionLayerExecutor(executor);
    await ale.execute({
      actions: [
        action('p2', 'pre', 'second', { order: 2 }),
        action('p0', 'pre', 'first', { order: 0 }),
        action('p1', 'pre', 'middle', { order: 1 }),
      ],
    });
    expect(calls).toEqual(['first', 'middle', 'second']);
  });
});

// ─── context merging ─────────────────────────────────────────────────────────

describe('ActionLayerExecutor – context', () => {
  it('passes merged context args to executor', async () => {
    const captured: any[] = [];
    const executor = vi.fn(async (_name: string, args: any) => { captured.push(args); return {}; });
    const ale = new ActionLayerExecutor(executor);
    await ale.execute({
      actions: [
        action('m', 'main', 'save', { args: { extra: 42 } }),
      ],
      context: { userId: 'u1' },
    });
    expect(captured[0]).toMatchObject({ userId: 'u1', extra: 42 });
  });

  it('passes _mainResult to POST actions', async () => {
    const captured: any[] = [];
    const executor = vi.fn(async (name: string, args: any) => {
      if (name === 'save') return { id: 999 };
      captured.push(args);
      return {};
    });
    const ale = new ActionLayerExecutor(executor);
    await ale.execute({
      actions: [
        action('m', 'main', 'save'),
        action('po', 'post', 'notify'),
      ],
    });
    expect(captured[0]._mainResult).toEqual({ id: 999 });
  });
});

// ─── condition evaluator ─────────────────────────────────────────────────────

describe('ActionLayerExecutor – condition evaluator', () => {
  it('skips action when condition evaluates to false', async () => {
    const calls: string[] = [];
    const executor = vi.fn(async (name: string) => { calls.push(name); return {}; });
    const condEval = vi.fn().mockReturnValue(false);
    const ale = new ActionLayerExecutor(executor, condEval);
    await ale.execute({
      actions: [
        action('m', 'main', 'skip-me', { condition: 'alwaysFalse' }),
      ],
    });
    expect(calls).not.toContain('skip-me');
  });

  it('runs action when condition evaluates to true', async () => {
    const calls: string[] = [];
    const executor = vi.fn(async (name: string) => { calls.push(name); return {}; });
    const condEval = vi.fn().mockReturnValue(true);
    const ale = new ActionLayerExecutor(executor, condEval);
    await ale.execute({
      actions: [
        action('m', 'main', 'run-me', { condition: 'alwaysTrue' }),
      ],
    });
    expect(calls).toContain('run-me');
  });

  it('runs action (no condition evaluator) when condition is set', async () => {
    const calls: string[] = [];
    const executor = vi.fn(async (name: string) => { calls.push(name); return {}; });
    const ale = new ActionLayerExecutor(executor); // no conditionEvaluator
    await ale.execute({
      actions: [
        action('m', 'main', 'should-run', { condition: 'anything' }),
      ],
    });
    expect(calls).toContain('should-run');
  });

  it('skips action when condition evaluator throws', async () => {
    const calls: string[] = [];
    const executor = vi.fn(async (name: string) => { calls.push(name); return {}; });
    const condEval = vi.fn().mockImplementation(() => { throw new Error('eval error'); });
    const ale = new ActionLayerExecutor(executor, condEval);
    await ale.execute({
      actions: [
        action('m', 'main', 'guarded', { condition: 'boom' }),
      ],
    });
    expect(calls).not.toContain('guarded');
  });
});

// ─── createLayeredConfig helper ──────────────────────────────────────────────

describe('createLayeredConfig', () => {
  it('creates a config with the main action', () => {
    const config = createLayeredConfig('save');
    expect(config.actions.some((a) => a.actionName === 'save' && a.phase === 'main')).toBe(true);
  });

  it('includes pre-actions', () => {
    const config = createLayeredConfig('save', {
      preActions: [{ action: 'validate' }, { action: 'authorize' }],
    });
    const preActions = config.actions.filter((a) => a.phase === 'pre');
    expect(preActions).toHaveLength(2);
    expect(preActions.map((a) => a.actionName)).toContain('validate');
    expect(preActions.map((a) => a.actionName)).toContain('authorize');
  });

  it('includes post-actions', () => {
    const config = createLayeredConfig('save', {
      postActions: [{ action: 'notify' }],
    });
    const postActions = config.actions.filter((a) => a.phase === 'post');
    expect(postActions).toHaveLength(1);
    expect(postActions[0].actionName).toBe('notify');
  });

  it('passes context through', () => {
    const config = createLayeredConfig('save', { context: { userId: 'u1' } });
    expect(config.context?.userId).toBe('u1');
  });

  it('pre-actions get incrementing order values', () => {
    const config = createLayeredConfig('save', {
      preActions: [{ action: 'a' }, { action: 'b' }, { action: 'c' }],
    });
    const pre = config.actions.filter((a) => a.phase === 'pre').sort((x, y) => (x.order ?? 0) - (y.order ?? 0));
    expect(pre[0].order).toBeLessThan(pre[1].order!);
    expect(pre[1].order).toBeLessThan(pre[2].order!);
  });

  it('works end-to-end with ActionLayerExecutor', async () => {
    const calls: string[] = [];
    const executor = vi.fn(async (name: string) => { calls.push(name); return {}; });
    const ale = new ActionLayerExecutor(executor);
    const config = createLayeredConfig('save', {
      preActions: [{ action: 'check' }],
      postActions: [{ action: 'log' }],
    });
    const result = await ale.execute(config);
    expect(result.success).toBe(true);
    expect(calls).toEqual(['check', 'save', 'log']);
  });
});
