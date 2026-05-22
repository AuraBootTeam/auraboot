import { describe, expect, it } from 'vitest';
import { sanitizeSmartDomProps } from '../domProps';

describe('sanitizeSmartDomProps', () => {
  it('removes schema-only props before they reach native DOM elements', () => {
    const domProps = sanitizeSmartDomProps({
      dataSource: { type: 'api' },
      refTarget: { targetModel: 'sys_user' },
      'placeholder:zh-CN': '负责人',
      multiline: true,
      showCount: true,
      'data-testid': 'field-owner',
      'aria-label': 'Owner',
      autoComplete: 'off',
    });

    expect(domProps).toEqual({
      'data-testid': 'field-owner',
      'aria-label': 'Owner',
      autoComplete: 'off',
    });
  });
});
