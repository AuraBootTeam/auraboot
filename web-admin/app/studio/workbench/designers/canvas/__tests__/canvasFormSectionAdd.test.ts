import { describe, expect, it, vi } from 'vitest';
import { createWidgetFieldConfig } from '../canvasFormSectionAdd';

describe('createWidgetFieldConfig', () => {
  it('creates unique field ids even within the same millisecond', () => {
    vi.spyOn(Date, 'now').mockReturnValue(1775700000000);

    const first = createWidgetFieldConfig('text');
    const second = createWidgetFieldConfig('text');

    expect(first.field).not.toBe(second.field);
    expect(first.field).toMatch(/^widget_1775700000000_\d+$/);
    expect(second.field).toMatch(/^widget_1775700000000_\d+$/);

    vi.restoreAllMocks();
  });
});
