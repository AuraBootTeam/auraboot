import React from 'react';
import { beforeEach, describe, it, expect, vi, afterEach } from 'vitest';
import { render, fireEvent, cleanup } from '@testing-library/react';
import {
  PropertyEditorRegistry,
  propertyEditorRegistry,
  type RegisteredPropertyEditorProps,
} from '../PropertyEditorRegistry';
import { SchemaBlockConfigPanel } from '../SchemaBlockConfigPanel';

// A retried run in the same worker may leave residue in the singleton
// registry; start every case from a known state (clear() is the test seam).
beforeEach(() => {
  propertyEditorRegistry.clear('designer.color');
});


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
    let conflict: unknown = 'no-throw';
    try {
      registry.register('sample', other);
    } catch (e) {
      conflict = e;
    }
    expect(conflict).toBeInstanceOf(Error);
    expect((conflict as Error).message).toContain('Conflicting');
    expect(registry.resolve('sample')).toBe(first);
  });
  it('supports owned extension cleanup without removing a newer registration', () => {
    const registry=new PropertyEditorRegistry();
    const first=()=>null, second=()=>null;
    const stop=registry.register('owned',first); stop();
    expect(()=>registry.resolve('owned')).toThrow('Unregistered');
    registry.register('owned',second); stop();
    expect(registry.resolve('owned')).toBe(second);
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
