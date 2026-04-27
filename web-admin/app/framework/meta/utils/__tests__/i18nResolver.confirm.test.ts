import { describe, expect, it } from 'vitest';
import { resolveConfirmDialog } from '../i18nResolver';

const missingTranslation = (key: string) => key;

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
