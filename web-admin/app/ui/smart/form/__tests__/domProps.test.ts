import { describe, expect, it } from 'vitest';
import { sanitizeSmartDomProps } from '../domProps';

describe('sanitizeSmartDomProps', () => {
  it('removes schema-only props before they reach native DOM elements', () => {
    const domProps = sanitizeSmartDomProps({
      dataSource: { type: 'api' },
      refTarget: { targetModel: 'sys_user' },
      uiSchema: { component: 'input' },
      'placeholder:zh-CN': '负责人',
      multiline: true,
      showCount: true,
      maxCount: 1,
      maxSize: 10,
      multiple: false,
      listType: 'text',
      showUploadList: true,
      onPreview: () => undefined,
      onRemove: () => undefined,
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
