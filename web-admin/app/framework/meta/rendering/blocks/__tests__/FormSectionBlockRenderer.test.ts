import { describe, expect, it } from 'vitest';
import {
  resolveSettingsCardDisplayValue,
  resolveSettingsCardFieldDisplayValue,
} from '../FormSectionBlockRenderer';

describe('resolveSettingsCardDisplayValue', () => {
  const t = (key: string) => key;

  it('renders the localized business label from a DSL value map', () => {
    expect(
      resolveSettingsCardDisplayValue(
        'pending_review',
        {
          pending_review: {
            'zh-CN': '待复核',
            en: 'Pending Review',
          },
        },
        'zh-CN',
        t,
      ),
    ).toBe('待复核');
  });

  it('keeps ordinary business values and renders an empty placeholder', () => {
    expect(resolveSettingsCardDisplayValue('AMOS生产仓', undefined, 'zh-CN', t)).toBe('AMOS生产仓');
    expect(resolveSettingsCardDisplayValue(null, undefined, 'zh-CN', t)).toBe('—');
  });

  it('reads the schema-safe value map from field props', () => {
    expect(
      resolveSettingsCardFieldDisplayValue(
        'pending_review',
        {
          props: {
            valueMap: {
              pending_review: { 'zh-CN': '待复核', en: 'Pending Review' },
            },
          },
        },
        'zh-CN',
        t,
      ),
    ).toBe('待复核');
  });
});
