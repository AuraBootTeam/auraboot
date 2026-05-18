import { describe, expect, it } from 'vitest';
import { resolveColumnLabel, resolveConfirmDialog, resolveFieldLabel } from '../i18nResolver';

const missingTranslation = (key: string) => key;
const translateWithPageSchemaLabels = (key: string) =>
  (
    {
      'field.page_schema.name.label': '页面名称',
      'field.page_schema.page_key.label': '页面标识',
      'common.field.name': '名称',
    } as Record<string, string>
  )[key] ?? key;

describe('resolveConfirmDialog', () => {
  it('provides business action fallbacks for cancellation, finalization, and conversion', () => {
    expect(resolveConfirmDialog('confirm.cancel', missingTranslation)).toEqual({
      title: '确认取消',
      content: '取消后当前业务状态将终止，确定继续吗？',
    });

    expect(resolveConfirmDialog('confirm.finalize', missingTranslation)).toEqual({
      title: '确认定稿',
      content: '定稿后需求将锁定，确定继续吗？',
    });

    expect(resolveConfirmDialog('confirm.convert', missingTranslation)).toEqual({
      title: '确认转换',
      content: '系统将基于当前记录生成下一环节业务单据，确定继续吗？',
    });
  });
});

describe('resolveFieldLabel', () => {
  it('prefers model-scoped field labels over common field fallbacks', () => {
    expect(resolveFieldLabel('name', 'page_schema', translateWithPageSchemaLabels)).toBe(
      '页面名称',
    );
  });
});

describe('resolveColumnLabel', () => {
  it('uses model-scoped field labels for columns without explicit labels', () => {
    expect(
      resolveColumnLabel({ field: 'page_key' }, 'page_schema', translateWithPageSchemaLabels),
    ).toBe('页面标识');
  });
});
