import { describe, it, expect } from 'vitest';
import { buildDefaultConfig, mergeConfig } from '../ToolbarActionGroup';
import type { ButtonConfig } from '~/framework/meta/schemas/types';
import type { ToolbarActionConfig } from '~/smart/types/savedView';

function makeButton(code: string, opts?: Partial<ButtonConfig>): ButtonConfig {
  return { code, ...opts } as ButtonConfig;
}

describe('buildDefaultConfig', () => {
  it('pins primary buttons and first 2 non-primary buttons', () => {
    const buttons: ButtonConfig[] = [
      makeButton('create', { primary: true }),
      makeButton('edit'),
      makeButton('delete'),
      makeButton('archive'),
      makeButton('export'),
    ];

    const config = buildDefaultConfig(buttons);

    expect(config).toHaveLength(5);
    // Primary button is pinned
    expect(config[0]).toEqual({ code: 'create', visible: true, pinned: true, order: 0 });
    // First 2 non-primary are pinned
    expect(config[1]).toEqual({ code: 'edit', visible: true, pinned: true, order: 1 });
    expect(config[2]).toEqual({ code: 'delete', visible: true, pinned: true, order: 2 });
    // Remaining non-primary are NOT pinned (3rd and 4th non-primary)
    expect(config[3]).toEqual({ code: 'archive', visible: true, pinned: false, order: 3 });
    expect(config[4]).toEqual({ code: 'export', visible: true, pinned: false, order: 4 });
  });

  it('pins buttons with variant="primary"', () => {
    const buttons: ButtonConfig[] = [
      makeButton('create', { variant: 'primary' }),
      makeButton('other1'),
      makeButton('other2'),
      makeButton('other3'),
    ];

    const config = buildDefaultConfig(buttons);
    // variant=primary counts as primary, so it's pinned
    expect(config[0].pinned).toBe(true);
    // First 2 non-primary pinned
    expect(config[1].pinned).toBe(true);
    expect(config[2].pinned).toBe(true);
    // 3rd non-primary overflows
    expect(config[3].pinned).toBe(false);
  });

  it('handles empty buttons array', () => {
    const config = buildDefaultConfig([]);
    expect(config).toEqual([]);
  });

  it('all visible by default', () => {
    const buttons = [makeButton('a'), makeButton('b')];
    const config = buildDefaultConfig(buttons);
    expect(config.every((c) => c.visible)).toBe(true);
  });
});

describe('mergeConfig', () => {
  const buttons: ButtonConfig[] = [
    makeButton('create', { primary: true }),
    makeButton('edit'),
    makeButton('delete'),
  ];

  it('returns default config when config is undefined', () => {
    const result = mergeConfig(buttons, undefined);
    expect(result).toHaveLength(3);
    // Should match buildDefaultConfig output
    expect(result[0].code).toBe('create');
    expect(result[0].pinned).toBe(true);
  });

  it('returns default config when config is empty array', () => {
    const result = mergeConfig(buttons, []);
    expect(result).toHaveLength(3);
  });

  it('respects visible/pinned/order from saved config', () => {
    const config: ToolbarActionConfig[] = [
      { code: 'delete', visible: true, pinned: true, order: 0 },
      { code: 'create', visible: true, pinned: false, order: 1 },
      { code: 'edit', visible: false, pinned: false, order: 2 },
    ];

    const result = mergeConfig(buttons, config);
    expect(result).toHaveLength(3);
    // Respects saved order
    expect(result[0].code).toBe('delete');
    expect(result[0].pinned).toBe(true);
    expect(result[1].code).toBe('create');
    expect(result[1].pinned).toBe(false);
    // edit is marked invisible
    expect(result[2].code).toBe('edit');
    expect(result[2].visible).toBe(false);
  });

  it('adds new buttons not in config to the end', () => {
    const config: ToolbarActionConfig[] = [
      { code: 'create', visible: true, pinned: true, order: 0 },
    ];

    const result = mergeConfig(buttons, config);
    // create from config + edit and delete as new
    expect(result).toHaveLength(3);
    expect(result[0].code).toBe('create');
    // New buttons appended with pinned=false
    const newButtons = result.filter((r) => r.code !== 'create');
    expect(newButtons.every((b) => !b.pinned)).toBe(true);
    expect(newButtons.every((b) => b.visible)).toBe(true);
  });

  it('removes config entries for buttons that no longer exist in DSL', () => {
    const config: ToolbarActionConfig[] = [
      { code: 'create', visible: true, pinned: true, order: 0 },
      { code: 'removed_button', visible: true, pinned: true, order: 1 },
    ];

    const result = mergeConfig(buttons, config);
    // removed_button should not appear in result
    expect(result.find((r) => r.code === 'removed_button')).toBeUndefined();
    // All DSL buttons should be present
    expect(result.map((r) => r.code).sort()).toEqual(['create', 'delete', 'edit']);
  });

  it('sorts final result by order', () => {
    const config: ToolbarActionConfig[] = [
      { code: 'edit', visible: true, pinned: false, order: 5 },
      { code: 'create', visible: true, pinned: true, order: 1 },
    ];

    const result = mergeConfig(buttons, config);
    // create (order 1) should come before edit (order 5)
    const createIdx = result.findIndex((r) => r.code === 'create');
    const editIdx = result.findIndex((r) => r.code === 'edit');
    expect(createIdx).toBeLessThan(editIdx);
  });
});
