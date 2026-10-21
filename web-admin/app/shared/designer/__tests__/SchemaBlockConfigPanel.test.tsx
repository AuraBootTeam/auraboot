import React from 'react';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { parse } from 'yaml';
import {
  buildColumnDetailSchemas,
  buildFilterDetailSchemas,
} from '~/plugins/core-designer/components/studio/workbench/designers/list-config/schema';
import { I18nProvider } from '~/contexts/I18nContext';
import { render, cleanup, fireEvent, within } from '@testing-library/react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { SchemaBlockConfigPanel, type ExtendedPropertySchema } from '../SchemaBlockConfigPanel';

afterEach(() => cleanup());

describe('SchemaBlockConfigPanel', () => {
  it.each([
    [
      'en-US',
      'General',
      'This group has 2 configurable settings.',
      'Choose which records appear first when the page opens.',
    ],
    ['zh-CN', '常规', '本组包含 2 个可配置项', '决定用户进入页面后最先看到的排序结果。'],
  ])(
    'localizes shared group hints and interpolates ungrouped counts in %s',
    (locale, general, countHint, sortHint) => {
      const catalog = parse(
        readFileSync(
          path.resolve(process.cwd(), `../platform/src/main/resources/i18n.${locale}.yaml`),
          'utf8',
        ),
      );
      const { getByText, getByRole } = render(
        <I18nProvider initialLocale={locale} initialData={catalog}>
          <SchemaBlockConfigPanel
            schemas={[
              { key: 'name', label: 'Name', type: 'text' },
              { key: 'note', label: 'Note', type: 'text' },
              { key: 'sort', label: 'Sort', type: 'text', group: 'Sorting' },
            ]}
            value={{}}
            onChange={vi.fn()}
          />
        </I18nProvider>,
      );
      expect(getByRole('heading', { name: general })).toBeInTheDocument();
      expect(getByText(countHint)).toBeInTheDocument();
      expect(getByText(sortHint)).toBeInTheDocument();
    },
  );

  it('keeps numeric and boolean controls usable without exposing raw schema type badges', () => {
    const onChange = vi.fn();
    const { queryByText, getByRole } = render(
      <SchemaBlockConfigPanel
        schemas={[
          { key: 'amount', label: 'Amount', type: 'number' },
          { key: 'enabled', label: 'Enabled', type: 'boolean' },
        ]}
        value={{ amount: 3, enabled: false }}
        onChange={onChange}
      />,
    );
    expect(queryByText('number', { exact: true })).not.toBeInTheDocument();
    expect(queryByText('boolean', { exact: true })).not.toBeInTheDocument();
    expect(getByRole('spinbutton')).toHaveValue(3);
    expect(getByRole('switch')).not.toBeChecked();
  });

  it.each(['en-US', 'zh-CN'])(
    'shows effective list defaults with localized labels in %s',
    (locale) => {
      const catalog = parse(
        readFileSync(
          path.resolve(process.cwd(), `../platform/src/main/resources/i18n.${locale}.yaml`),
          'utf8',
        ),
      );
      const t = (key: string) => key.split('.').reduce((node, part) => node[part], catalog);
      const onChange = vi.fn();
      const { getByTestId } = render(
        <I18nProvider initialLocale={locale} initialData={catalog}>
          <SchemaBlockConfigPanel
            schemas={[...buildColumnDetailSchemas(t), ...buildFilterDetailSchemas(t)]}
            value={Object.freeze({})}
            onChange={onChange}
          />
        </I18nProvider>,
      );
      for (const [key, label] of [
        ['align', 'left_align'],
        ['renderer', 'text'],
        ['displayMode', 'inline'],
      ]) {
        expect(
          within(getByTestId(`schema-config-field-${key}`)).getByRole('combobox'),
        ).toHaveTextContent(catalog.list_editor[label]);
      }
      expect(onChange).not.toHaveBeenCalled();
    },
  );

  it('displays a missing schema default without writing it into the configuration', () => {
    const value = Object.freeze({ note: 'original' });
    const onChange = vi.fn();
    const { getByTestId } = render(
      <SchemaBlockConfigPanel
        schemas={[
          {
            key: 'mode',
            label: 'Mode',
            type: 'select',
            defaultValue: 'left',
            options: [
              { label: 'Left', value: 'left' },
              { label: 'Right', value: 'right' },
            ],
          },
          { key: 'note', label: 'Note', type: 'text' },
        ]}
        value={value}
        onChange={onChange}
      />,
    );
    expect(within(getByTestId('schema-config-field-mode')).getByRole('combobox')).toHaveTextContent(
      'Left',
    );
    expect(onChange).not.toHaveBeenCalled();
    fireEvent.change(within(getByTestId('schema-config-field-note')).getByRole('textbox'), {
      target: { value: 'edited' },
    });
    expect(onChange).toHaveBeenCalledWith({ note: 'edited' }, 'note');
    expect(value).toEqual({ note: 'original' });
  });

  it.each([false, 0, '', null])(
    'preserves explicit values instead of applying a default: %s',
    (value) => {
      const { getByRole } = render(
        <SchemaBlockConfigPanel
          schemas={[{ key: 'note', label: 'Note', type: 'text', defaultValue: 'fallback' }]}
          value={{ note: value }}
          onChange={vi.fn()}
        />,
      );
      expect(getByRole('textbox')).toHaveValue(value == null ? '' : String(value));
    },
  );

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
      <SchemaBlockConfigPanel schemas={schemas} value={{ mode: 'c' }} onChange={vi.fn()} />,
    );
    expect(queryByTestId('schema-config-field-extra')).not.toBeInTheDocument();
    expect(queryByTestId('schema-config-field-multi')).not.toBeInTheDocument();
  });

  it('shows field when dependsOn.value matches', () => {
    const { getByTestId } = render(
      <SchemaBlockConfigPanel schemas={schemas} value={{ mode: 'a' }} onChange={vi.fn()} />,
    );
    expect(getByTestId('schema-config-field-extra')).toBeInTheDocument();
  });

  it('shows field when dependsOn.anyOf includes value', () => {
    const { queryByTestId, getByTestId } = render(
      <SchemaBlockConfigPanel schemas={schemas} value={{ mode: 'b' }} onChange={vi.fn()} />,
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
      <SchemaBlockConfigPanel schemas={schemas} value={{ mode: 'c' }} onChange={vi.fn()} />,
    );
    expect(queryByTestId('schema-config-group-Advanced')).not.toBeInTheDocument();
  });
});

describe('nested designer configuration contract', () => {
  it('edits nested numeric properties without flattening paths or losing adjacent values', () => {
    const changed = vi.fn();
    const value = Object.freeze({
      style: Object.freeze({ fontSize: 10, color: '#123456' }),
      untouched: 'kept',
    });
    const { getByRole } = render(
      <SchemaBlockConfigPanel
        pathMode="nested"
        density="compact"
        schemas={[{ key: 'style.fontSize', label: 'Font size', type: 'number', min: 6, max: 48 }]}
        value={value}
        onChange={changed}
      />,
    );
    expect(getByRole('spinbutton')).toHaveValue(10);
    fireEvent.change(getByRole('spinbutton'), { target: { value: '24' } });
    expect(changed).toHaveBeenCalledWith(
      { style: { fontSize: 24, color: '#123456' }, untouched: 'kept' },
      'style.fontSize',
    );
    expect(value.style.fontSize).toBe(10);
  });
  it('evaluates nested dependencies and effective defaults without mutating the document', () => {
    const changed = vi.fn();
    const { getByTestId } = render(
      <SchemaBlockConfigPanel
        pathMode="nested"
        schemas={[
          { key: 'summary.enabled', label: 'Enabled', type: 'boolean', defaultValue: true },
          {
            key: 'summary.label',
            label: 'Summary label',
            type: 'text',
            dependsOn: { field: 'summary.enabled', value: true },
          },
        ]}
        value={{}}
        onChange={changed}
      />,
    );
    expect(getByTestId('schema-config-field-summary.label')).toBeInTheDocument();
    expect(changed).not.toHaveBeenCalled();
  });
  it('does not persist invalid numeric bounds and exposes a field error', () => {
    const changed = vi.fn();
    const { getByRole } = render(
      <SchemaBlockConfigPanel
        schemas={[{ key: 'opacity', label: 'Opacity', type: 'number', min: 0, max: 1 }]}
        value={{ opacity: 0.1 }}
        onChange={changed}
      />,
    );
    fireEvent.change(getByRole('spinbutton'), { target: { value: '2' } });
    expect(changed).not.toHaveBeenCalled();
    expect(getByRole('alert')).toBeInTheDocument();
  });
});
