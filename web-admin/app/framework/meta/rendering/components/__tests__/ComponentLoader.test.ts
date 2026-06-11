import { describe, expect, it } from 'vitest';
import { sanitizeRuntimeComponentProps } from '../ComponentLoader';

describe('sanitizeRuntimeComponentProps', () => {
  it('removes model/design metadata before invoking runtime field components', () => {
    const props = sanitizeRuntimeComponentProps({
      name: 'crawler_status',
      value: 'RUNNING',
      uiSchema: { component: 'select' },
      'data-testid': 'crawler-status',
    });

    expect(props).toEqual({
      name: 'crawler_status',
      value: 'RUNNING',
      'data-testid': 'crawler-status',
    });
  });
});
