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
  it('surfaces both the DSL governance pages and the integrated console preview', () => {
    const menus = readMenus();
    const parent = menus.find((menu) => menu.code === 'decisionops_console');
    const preview = menus.find((menu) => menu.code === 'decisionops_console_preview');
    const rollout = menus.find((menu) => menu.code === 'decisionops_rollouts');

    expect(parent).toMatchObject({
      path: '/p/decisionops_rollouts',
      visible: true,
    });
    expect(preview).toMatchObject({
      parentCode: 'decisionops_console',
      name: '综合控制台预览',
      path: '/decision-ops',
      visible: true,
      extension: expect.objectContaining({ implementation: 'react-console-preview' }),
    });
    expect(rollout).toMatchObject({
      parentCode: 'decisionops_console',
      path: '/p/decisionops_rollouts',
      extension: expect.objectContaining({ implementation: 'dsl' }),
    });
  });
});
