import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, it, expect, vi } from 'vitest';
import {
  SchemaBlockConfigPanel,
  type ExtendedPropertySchema,
} from '../SchemaBlockConfigPanel';

describe('SchemaBlockConfigPanel', () => {
  afterEach(() => {
    cleanup();
  });

  const schemas: ExtendedPropertySchema<string>[] = [
    { key: 'name', label: 'Name', type: 'text', group: 'Basic' },
    { key: 'icon', label: 'Icon', type: 'icon', group: 'Basic' },
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
    const { queryByTestId } = render(
      <SchemaBlockConfigPanel
        schemas={schemas}
        value={{ mode: 'c' }}
        onChange={vi.fn()}
      />,
    );
    expect(queryByTestId('schema-config-field-extra')).not.toBeInTheDocument();
    expect(queryByTestId('schema-config-field-multi')).not.toBeInTheDocument();
  });

  it('shows field when dependsOn.value matches', () => {
    const { getByTestId } = render(
      <SchemaBlockConfigPanel
        schemas={schemas}
        value={{ mode: 'a' }}
        onChange={vi.fn()}
      />,
    );
    expect(getByTestId('schema-config-field-extra')).toBeInTheDocument();
  });

  it('shows field when dependsOn.anyOf includes value', () => {
    const { queryByTestId, getByTestId } = render(
      <SchemaBlockConfigPanel
        schemas={schemas}
        value={{ mode: 'b' }}
        onChange={vi.fn()}
      />,
    );
    expect(queryByTestId('schema-config-field-extra')).not.toBeInTheDocument();
    expect(getByTestId('schema-config-field-multi')).toBeInTheDocument();
  });

  it('renders group headings for grouped schemas', () => {
    const { getByTestId } = render(
      <SchemaBlockConfigPanel schemas={schemas} value={{}} onChange={vi.fn()} />,
    );
    expect(getByTestId('schema-config-group-Basic')).toBeInTheDocument();
  });

  it('renders icon picker fields through the shared schema renderer', () => {
    const { getByTestId } = render(
      <SchemaBlockConfigPanel schemas={schemas} value={{}} onChange={vi.fn()} />,
    );
    expect(getByTestId('schema-config-field-icon')).toBeInTheDocument();
  });

  it('hides entire group when all its schemas are dependsOn-hidden', () => {
    const { queryByTestId } = render(
      <SchemaBlockConfigPanel
        schemas={schemas}
        value={{ mode: 'c' }}
        onChange={vi.fn()}
      />,
    );
    expect(queryByTestId('schema-config-group-Advanced')).not.toBeInTheDocument();
  });
});
