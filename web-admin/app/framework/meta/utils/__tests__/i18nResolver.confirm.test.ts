import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { URL } from 'node:url';
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

  it('treats a literal (non-key) string confirm as the dialog content', () => {
    // WMS-UX-05: DSL `confirm: "将按通知明细生成收货入库单…是否继续?"` is plain
    // copy, not an i18n key — the dialog must show it verbatim, not an empty body.
    expect(
      resolveConfirmDialog('将按通知明细生成收货入库单（草稿，收货位默认取本仓收货位）。是否继续?', missingTranslation),
    ).toEqual({
      title: '确认',
      content: '将按通知明细生成收货入库单（草稿，收货位默认取本仓收货位）。是否继续?',
    });
  });

  it('keeps key-shaped strings on the i18n path', () => {
    expect(resolveConfirmDialog('confirm.someUnknownKey', missingTranslation)).toEqual({
      title: '确认',
      content: '',
    });
  });

  it('accepts localized inline confirm content from page DSL', () => {
    expect(
      resolveConfirmDialog(
        {
          'zh-CN': '确认为该成员生成新的临时密码？临时密码只会显示一次。',
          'en-US': 'Generate a new temporary password for this member?',
        },
        missingTranslation,
      ),
    ).toEqual({
      title: '确认',
      content: '确认为该成员生成新的临时密码？临时密码只会显示一次。',
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

describe('canonical announcement submission confirmation', () => {
  type ConfirmButton = { code: string; confirm?: string };
  type ConfirmPage = {
    pageKey: string;
    blocks: Array<{ buttons?: ConfirmButton[]; rowActions?: ConfirmButton[] }>;
  };
  const pages: ConfirmPage[] = JSON.parse(readFileSync(
    new URL('../../../../../../plugins/core-announcement/config/pages.json', import.meta.url),
    'utf8',
  ));
  const resources: Array<Record<string, string>> = JSON.parse(readFileSync(
    new URL('../../../../../../plugins/core-announcement/config/i18n.json', import.meta.url),
    'utf8',
  ));
  const expected = {
    'zh-CN': {
      title: '提交公告审批',
      content: '提交后将冻结正文、附件和通知设置；审批通过后按发布安排执行。确定提交吗？',
    },
    'en-US': {
      title: 'Submit announcement approval',
      content: 'Content, attachments and notification settings will be frozen. Approval will authorize the configured release. Submit now?',
    },
  };

  for (const pageKey of ['ab_announcement_list', 'ab_announcement_detail']) {
    for (const locale of ['zh-CN', 'en-US'] as const) {
      it(`resolves the ${pageKey} submission title and content in ${locale}`, () => {
        const page = pages.find(candidate => candidate.pageKey === pageKey);
        expect(page).toBeDefined();
        const buttons = page!.blocks.flatMap(block => [
          ...(block.rowActions ?? []), ...(block.buttons ?? []),
        ]).filter(button => button.code === 'submit_approval');
        expect(buttons).toHaveLength(1);
        expect(typeof buttons[0].confirm).toBe('string');
        const translate = (key: string) => resources.find(resource => resource.key === key)?.[locale] ?? key;
        expect(resolveConfirmDialog(buttons[0].confirm!, translate)).toEqual(expected[locale]);
      });
    }
  }
});
