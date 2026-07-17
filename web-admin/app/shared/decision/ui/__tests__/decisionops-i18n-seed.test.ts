import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const REQUIRED_DECISIONOPS_HEADER_LABELS: Record<string, string> = {
  'decisionops.header.eyebrow': '策略工作台',
  'decisionops.header.definitions': '规则定义',
  'decisionops.header.policies': '事件策略',
  'decisionops.header.today': '今日评估',
  'decisionops.header.openWorkbench': '进入工作区',
};

describe('DecisionOps i18n seed coverage', () => {
  it('contains zh-CN labels for the product header counters', () => {
    const entries = JSON.parse(
      readFileSync('../plugins/platform-admin/config/i18n.json', 'utf8'),
    ) as Array<Record<string, string>>;

    for (const [key, zhLabel] of Object.entries(REQUIRED_DECISIONOPS_HEADER_LABELS)) {
      const entry = entries.find((item) => item.key === key);
      expect(entry?.['zh-CN'], `${key} must have a zh-CN label`).toBe(zhLabel);
      expect(entry?.['en-US'], `${key} must have an en-US label`).toBeTruthy();
    }
  });
});
