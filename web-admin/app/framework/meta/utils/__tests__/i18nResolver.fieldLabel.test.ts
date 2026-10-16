import { describe, expect, it } from 'vitest';
import { resolveExtensionDisplayName } from '../i18nResolver';
describe('authored field labels', () => {
  it('uses the persisted UI label instead of a technical code for generated forms', () => {
    expect(
      resolveExtensionDisplayName(
        undefined,
        'launch_followup_customer_name',
        'zh-CN',
        ' 客户名称 ',
      ),
    ).toBe('客户名称');
  });
  it('keeps existing localized-extension labels when no UI label is authored', () => {
    expect(
      resolveExtensionDisplayName({ 'displayName:zh-CN': '客户名称' }, 'customer', 'zh-CN'),
    ).toBe('客户名称');
    expect(resolveExtensionDisplayName({ displayName: 'Customer' }, 'customer', 'en-US', ' ')).toBe(
      'Customer',
    );
  });
});
