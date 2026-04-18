import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import {
  SchemaBlockConfigPanel,
  type ExtendedPropertySchema,
} from '../SchemaBlockConfigPanel';

describe('SchemaBlockConfigPanel', () => {
  const schemas: ExtendedPropertySchema<string>[] = [
    { key: 'name', label: 'Name', type: 'text', group: 'Basic' },
    {
      key: 'mode',
      label: 'Mode',
      type: 'select',
      group: 'Basic',
      options: [
        { label: 'A', value: 'a' },
        { label: 'B', value: 'b' },
      ],
    },
    {
      key: 'extra',
      label: 'Extra',
      type: 'text',
      group: 'Advanced',
      dependsOn: { field: 'mode', value: 'a' },
    },
    {
      key: 'multi',
      label: 'Multi',
      type: 'text',
      group: 'Advanced',
      dependsOn: { field: 'mode', anyOf: ['a', 'b'] },
    },
  ];

  it('hides field when dependsOn condition not met', () => {
    render(
      <SchemaBlockConfigPanel
        schemas={schemas}
        value={{ mode: 'c' }}
        onChange={vi.fn()}
      />,
    );
    expect(screen.queryByText('Extra')).not.toBeInTheDocument();
    expect(screen.queryByText('Multi')).not.toBeInTheDocument();
  });

  it('shows field when dependsOn.value matches', () => {
    render(
      <SchemaBlockConfigPanel
        schemas={schemas}
        value={{ mode: 'a' }}
        onChange={vi.fn()}
      />,
    );
    expect(screen.getByText('Extra')).toBeInTheDocument();
  });

  it('shows field when dependsOn.anyOf includes value', () => {
    render(
      <SchemaBlockConfigPanel
        schemas={schemas}
        value={{ mode: 'b' }}
        onChange={vi.fn()}
      />,
    );
    expect(screen.queryByText('Extra')).not.toBeInTheDocument(); // value: 'a' not matched
    expect(screen.getByText('Multi')).toBeInTheDocument(); // anyOf includes 'b'
  });

  it('renders group headings for grouped schemas', () => {
    render(
      <SchemaBlockConfigPanel schemas={schemas} value={{}} onChange={vi.fn()} />,
    );
    expect(screen.getByText('Basic')).toBeInTheDocument();
  });

  it('hides entire group when all its schemas are dependsOn-hidden', () => {
    render(
      <SchemaBlockConfigPanel
        schemas={schemas}
        value={{ mode: 'c' }}
        onChange={vi.fn()}
      />,
    );
    // Both Advanced-group schemas depend on mode being 'a' or ['a','b']
    expect(screen.queryByText('Advanced')).not.toBeInTheDocument();
  });
});
