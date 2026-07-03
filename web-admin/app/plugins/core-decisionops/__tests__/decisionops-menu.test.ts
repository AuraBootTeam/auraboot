import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

interface MenuConfig {
  code: string;
  parentCode: string | null;
  name: string;
  path: string;
  visible?: boolean;
  extension?: Record<string, unknown>;
}

function readMenus(): MenuConfig[] {
  const file = resolve(process.cwd(), '../plugins/core-decisionops/config/menus.json');
  return JSON.parse(readFileSync(file, 'utf8')) as MenuConfig[];
}

describe('DecisionOps menu entries', () => {
  it('surfaces Strategy Studio as the default DecisionOps entry while keeping DSL governance pages', () => {
    const menus = readMenus();
    const parent = menus.find((menu) => menu.code === 'decisionops_console');
    const rollout = menus.find((menu) => menu.code === 'decisionops_rollouts');
    const defaultEntries = menus.filter(
      (menu) => menu.path === '/decision-ops' && menu.visible !== false,
    );

    expect(parent).toMatchObject({
      path: '/decision-ops',
      visible: true,
      extension: expect.objectContaining({ platforms: ['web'] }),
    });
    expect(defaultEntries).toHaveLength(1);
    expect(defaultEntries[0]?.code).toBe('decisionops_console');
    expect(rollout).toMatchObject({
      parentCode: 'decisionops_console',
      path: '/p/decisionops_rollouts',
      extension: expect.objectContaining({ implementation: 'dsl' }),
    });
  });
});
