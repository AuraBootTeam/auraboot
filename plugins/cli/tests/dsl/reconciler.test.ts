import { mkdtempSync, rmSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { PluginFiles } from '../../src/utils/plugin-loader.js';
import { computePlan } from '../../src/dsl/plan.js';
import { fingerprint, readState, writeState } from '../../src/dsl/state.js';

function plugin(resourceFiles: Record<string, any[]>): PluginFiles {
  return {
    dir: '/tmp/x',
    configDir: '/tmp/x/config',
    manifest: { pluginId: 'com.acme.demo', version: '1.0.0' } as any,
    resourceFiles: new Map(Object.entries(resourceFiles)),
  } as PluginFiles;
}

describe('fingerprint', () => {
  it('keys each resource by type + natural id and is deterministic', () => {
    const state = fingerprint(
      plugin({ models: [{ code: 'order' }, { code: 'line' }], pages: [{ pageKey: 'order_list' }] }),
    );
    expect(Object.keys(state.resources).sort()).toEqual([
      'models:line',
      'models:order',
      'pages:order_list',
    ]);
    // deterministic
    const again = fingerprint(plugin({ models: [{ code: 'order' }] }));
    expect(again.resources['models:order']).toBe(
      fingerprint(plugin({ models: [{ code: 'order' }] })).resources['models:order'],
    );
  });

  it('changes the hash when a resource body changes', () => {
    const a = fingerprint(plugin({ models: [{ code: 'order', label: 'Order' }] }));
    const b = fingerprint(plugin({ models: [{ code: 'order', label: 'Orders' }] }));
    expect(a.resources['models:order']).not.toBe(b.resources['models:order']);
  });
});

describe('computePlan', () => {
  const base = fingerprint(plugin({ models: [{ code: 'order', v: 1 }, { code: 'line', v: 1 }] }));

  it('L0 when nothing changed', () => {
    const plan = computePlan(base, base);
    expect(plan.changed).toBe(false);
    expect(plan.riskLevel).toBe('L0');
    expect(plan).toMatchObject({ create: [], update: [], destroy: [] });
  });

  it('L1 when only creating (prior is null)', () => {
    const plan = computePlan(base, null);
    expect(plan.create.sort()).toEqual(['models:line', 'models:order']);
    expect(plan.update).toEqual([]);
    expect(plan.destroy).toEqual([]);
    expect(plan.riskLevel).toBe('L1');
    expect(plan.changed).toBe(true);
  });

  it('L2 when a resource body changed (update, no destroy)', () => {
    const desired = fingerprint(plugin({ models: [{ code: 'order', v: 2 }, { code: 'line', v: 1 }] }));
    const plan = computePlan(desired, base);
    expect(plan.update).toEqual(['models:order']);
    expect(plan.destroy).toEqual([]);
    expect(plan.riskLevel).toBe('L2');
  });

  it('L3 when a resource is destroyed', () => {
    const desired = fingerprint(plugin({ models: [{ code: 'order', v: 1 }] }));
    const plan = computePlan(desired, base);
    expect(plan.destroy).toEqual(['models:line']);
    expect(plan.riskLevel).toBe('L3');
  });

  it('L3 dominates even when there are also creates and updates', () => {
    const desired = fingerprint(plugin({ models: [{ code: 'order', v: 9 }, { code: 'new', v: 1 }] }));
    const plan = computePlan(desired, base);
    expect(plan.create).toEqual(['models:new']);
    expect(plan.update).toEqual(['models:order']);
    expect(plan.destroy).toEqual(['models:line']);
    expect(plan.riskLevel).toBe('L3');
  });
});

describe('state persistence', () => {
  let dir: string;
  beforeEach(() => {
    dir = mkdtempSync(join(tmpdir(), 'aura-dslstate-'));
  });
  afterEach(() => {
    rmSync(dir, { recursive: true, force: true });
  });

  it('returns null when no state file exists', () => {
    expect(readState(join(dir, 'nope', 'state.json'))).toBeNull();
  });

  it('round-trips a written state (and a plan against it is L0)', () => {
    const state = fingerprint(plugin({ models: [{ code: 'order', v: 1 }] }));
    const path = join(dir, '.aura', 'dsl-state.json');
    writeState(path, state);
    const loaded = readState(path);
    expect(loaded).toEqual(state);
    expect(computePlan(state, loaded).riskLevel).toBe('L0');
  });
});
