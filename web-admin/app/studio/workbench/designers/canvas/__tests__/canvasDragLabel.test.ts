import { describe, expect, it } from 'vitest';
import { initRegistry } from '~/studio/registry/init';
import { resolveCanvasDragLabel } from '../canvasDragLabel';
import type { CanvasBlock } from '~/studio/domain/canvas/types';

initRegistry();

describe('resolveCanvasDragLabel', () => {
  const blocks: CanvasBlock[] = [
    {
      id: 'block_form',
      blockType: 'form-section',
      config: {
        fields: [
          { field: 'widget_text', component: 'text' },
          'customer_name',
        ],
      },
      layout: { col: 0, colSpan: 12, rowSpan: 1, order: 0 },
    },
    {
      id: 'block_table',
      blockType: 'table',
      config: {},
      layout: { col: 0, colSpan: 12, rowSpan: 1, order: 1 },
    },
  ];

  it('resolves widget palette drags to widget display names', () => {
    expect(resolveCanvasDragLabel('widget:text', blocks)).toBe('Text Input');
  });

  it('resolves palette block drags to block display names', () => {
    expect(resolveCanvasDragLabel('palette:table', blocks)).toBe('Table');
  });

  it('resolves existing field-item drags to field labels', () => {
    expect(resolveCanvasDragLabel('field-item:block_form:0', blocks)).toBe('Text Input');
    expect(resolveCanvasDragLabel('field-item:block_form:1', blocks)).toBe('customer_name');
  });

  it('resolves existing block drags to block labels', () => {
    expect(resolveCanvasDragLabel('block_form', blocks)).toBe('Form Section · 2 fields');
    expect(resolveCanvasDragLabel('block_table', blocks)).toBe('Table');
  });
});
