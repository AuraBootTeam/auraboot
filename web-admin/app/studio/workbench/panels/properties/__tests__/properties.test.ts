import { describe, it, expect } from 'vitest';
import { PropertyPanel } from '~/studio/workbench/panels/properties/index';

describe('studio property exports', () => {
  it('exposes the PropertyPanel component', () => {
    expect(PropertyPanel).toBeDefined();
    expect(typeof PropertyPanel).toBe('function');
  });
});
