import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

interface MenuConfig {
  code: string;
  parentCode: string | null;
  name: string;
  'name:zh-CN'?: string;
  'name:en'?: string;
  path: string;
  visible?: boolean;
  extension?: Record<string, unknown>;
}

function readMenus(): MenuConfig[] {
  const file = resolve(process.cwd(), '../plugins/core-decisionops/config/menus.json');
  return JSON.parse(readFileSync(file, 'utf8')) as MenuConfig[];
}

describe('DecisionOps menu entries', () => {
  it('surfaces Rule Center as the default Strategy Studio entry while keeping DSL governance pages', () => {
    const menus = readMenus();
    const parent = menus.find((menu) => menu.code === 'decisionops_console');
    const eventPolicy = menus.find((menu) => menu.code === 'decisionops_event_policies');
    const conditionFragments = menus.find((menu) => menu.code === 'decisionops_condition_fragments');
    const rollout = menus.find((menu) => menu.code === 'decisionops_rollouts');
    const defaultEntries = menus.filter(
      (menu) => menu.path === '/decision-ops' && menu.visible !== false,
    );

    expect(parent).toMatchObject({
      name: '规则中心',
      'name:zh-CN': '规则中心',
      'name:en': 'Rule Center',
      path: '/decision-ops',
      visible: true,
      extension: expect.objectContaining({ platforms: ['web'] }),
    });
    expect(eventPolicy).toMatchObject({
      name: '事件策略',
      'name:zh-CN': '事件策略',
      'name:en': 'Event Policy',
    });
    expect(conditionFragments).toMatchObject({
      parentCode: 'decisionops_console',
      name: '条件片段库',
      'name:zh-CN': '条件片段库',
      'name:en': 'Condition Fragments',
      path: '/p/decisionops_condition_fragments',
      visible: true,
      extension: expect.objectContaining({ implementation: 'dsl' }),
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
