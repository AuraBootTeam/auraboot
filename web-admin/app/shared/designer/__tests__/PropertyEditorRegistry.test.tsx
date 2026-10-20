import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, fireEvent, cleanup } from '@testing-library/react';
import {
  PropertyEditorRegistry,
  propertyEditorRegistry,
  type RegisteredPropertyEditorProps,
} from '../PropertyEditorRegistry';
import { SchemaBlockConfigPanel } from '../SchemaBlockConfigPanel';

afterEach(cleanup);
describe('registered property controls', () => {
  it('fails closed for unknown controls and conflicting registrations', () => {
    const registry = new PropertyEditorRegistry();
    const first = () => null,
      other = () => null;
    expect(() => registry.resolve('missing')).toThrow('Unregistered');
    registry.register('sample', first);
    registry.register('sample', first);
    expect(registry.resolve('sample')).toBe(first);
    expect(() => registry.register('sample', other)).toThrow('Conflicting');
    expect(registry.resolve('sample')).toBe(first);
  });
  it('introduces a complex control through schema only and enforces read-only writes at the host', () => {
    const Editor = ({ adapter }: RegisteredPropertyEditorProps) => (
      <button onClick={() => adapter.setValue({ field: 'budget', value: 100 })}>
        Choose business mapping
      </button>
    );
    propertyEditorRegistry.register('test.mapping', Editor);
    const changed = vi.fn();
    const props = {
      schemas: [
        { key: 'mapping', label: 'Mapping', type: 'text' as const, editor: 'test.mapping' },
      ],
      value: { mapping: {} },
      onChange: changed,
    };
    const { getByRole, rerender } = render(<SchemaBlockConfigPanel {...props} />);
    fireEvent.click(getByRole('button'));
    expect(changed).toHaveBeenCalledExactlyOnceWith(
      { mapping: { field: 'budget', value: 100 } },
      'mapping',
    );
    changed.mockClear();
    rerender(<SchemaBlockConfigPanel {...props} readonly />);
    fireEvent.click(getByRole('button'));
    expect(changed).not.toHaveBeenCalled();
  });
});
