import React, { useState } from 'react';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import JsonEditor from '../JsonEditor';
import {
  structuredJsonError,
  structuredJsonDefault,
  structuredJsonValidator,
  type StructuredJsonSchema,
} from '~/framework/smart/schema/structured-json';

const remote = vi.hoisted(() => ({ requests: [] as unknown[] }));
// Substitute option transport only; exercise the actual Smart controls and JSON binding.
vi.mock('~/framework/meta/hooks/useFieldDataSource', () => ({
  useFieldDataSource: (props: any) => {
    if (props.dataSource) remote.requests.push(props.dataSource);
    return {
      options: props.dataSource
        ? [{ value: 'TEAM-OWNED', label: 'Delivery team' }]
        : props.staticOptions || [],
      loading: false,
      error: null,
      refetch: vi.fn(),
    };
  },
}));

beforeAll(() => {
  window.HTMLElement.prototype.scrollIntoView = vi.fn();
  window.HTMLElement.prototype.hasPointerCapture = vi.fn();
  window.HTMLElement.prototype.releasePointerCapture = vi.fn();
});

const schema: StructuredJsonSchema = {
  type: 'object',
  additionalProperties: true,
  properties: {
    repositories: {
      type: 'array',
      title: 'Repositories',
      maxItems: 5,
      minItems: 1,
      items: {
        type: 'object',
        title: 'Repository',
        additionalProperties: false,
        required: ['name'],
        properties: {
          name: { type: 'string', title: 'Repository name', minLength: 1 },
        },
      },
    },
  },
  required: ['repositories'],
};

function setup(initial: unknown, shape = schema, extra: Record<string, unknown> = {}) {
  const changes: unknown[] = [];
  function Harness() {
    const [value, setValue] = useState(JSON.stringify(initial));
    return (
      <JsonEditor
        name="draft"
        mode="structured"
        structuredSchema={shape}
        value={value}
        context={{ locale: 'en', t: (key: string) => key } as any}
        onChange={(next) => {
          changes.push(next);
          setValue(next);
        }}
        {...extra}
      />
    );
  }
  render(<Harness />);
  return { changes, latest: () => JSON.parse(changes.at(-1) as string) };
}

describe('Structured JSON field controls', () => {
  it('renders business fields without a raw JSON textarea and makes no mount-time writes', () => {
    const { changes } = setup({ repositories: [{ name: 'Core' }] });
    expect(screen.getByRole('textbox', { name: /^Repository name\*?$/ })).toHaveValue('Core');
    expect(document.querySelector('textarea')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Format' })).toBeNull();
    expect(changes).toEqual([]);
  });

  it('keeps hidden properties out of the grid while retaining their data', () => {
    const { latest } = setup(
      { visible: 'Before', hidden: 'retained' },
      {
        type: 'object',
        required: ['visible', 'hidden'],
        properties: {
          visible: { type: 'string', title: 'Visible' },
          hidden: { type: 'string', title: 'Hidden', 'x-hidden': true },
        },
      },
    );
    const field = screen.getByRole('textbox', { name: /^Visible\*?$/ });
    const grid = field.closest('fieldset')!.querySelector('.grid')!;
    expect(grid.children).toHaveLength(1);
    fireEvent.change(field, { target: { value: 'After' } });
    expect(latest()).toEqual({ visible: 'After', hidden: 'retained' });
  });

  it('preserves sibling-step data and exact remaining row values after removal', () => {
    const initial = {
      repositories: [{ name: 'Core' }, { name: 'Quote' }],
      deploymentUnits: [{ name: 'backend' }],
    };
    const { latest } = setup(initial);
    fireEvent.change(screen.getAllByRole('textbox', { name: /^Repository name\*?$/ })[1], {
      target: { value: 'Quote revised' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Remove Repositories 1' }));
    expect(screen.getByRole('textbox', { name: /^Repository name\*?$/ })).toHaveValue(
      'Quote revised',
    );
    expect(latest()).toEqual({
      repositories: [{ name: 'Quote revised' }],
      deploymentUnits: [{ name: 'backend' }],
    });
    expect(initial.repositories[1].name).toBe('Quote');
  });

  it('adds bounded rows, reports missing business fields and does not mutate defaults', () => {
    const shape: StructuredJsonSchema = {
      ...schema,
      properties: {
        repositories: {
          ...schema.properties!.repositories,
          maxItems: 1,
          items: { ...schema.properties!.repositories.items!, default: { name: '' } },
        },
      },
    };
    const { latest } = setup({ repositories: [] }, shape);
    fireEvent.click(screen.getByRole('button', { name: 'Add Repositories' }));
    expect(screen.getByRole('button', { name: 'Add Repositories' })).toBeDisabled();
    expect(screen.getByRole('alert')).toHaveTextContent('Repository name');
    fireEvent.change(screen.getByRole('textbox', { name: /^Repository name\*?$/ }), {
      target: { value: 'Core' },
    });
    expect(latest()).toEqual({ repositories: [{ name: 'Core' }] });
    expect(shape.properties!.repositories.items!.default).toEqual({ name: '' });
  });

  it('edits nested object arrays while preserving outer values', () => {
    const shape: StructuredJsonSchema = {
      type: 'object',
      properties: {
        units: {
          type: 'array',
          title: 'Units',
          items: {
            type: 'object',
            title: 'Unit',
            properties: {
              bindings: {
                type: 'array',
                title: 'Bindings',
                items: {
                  type: 'object',
                  title: 'Binding',
                  properties: {
                    component: { type: 'string', title: 'Component' },
                  },
                },
              },
            },
          },
        },
      },
    };
    const { latest } = setup(
      { units: [{ bindings: [{ component: 'backend' }] }], other: 'retained' },
      shape,
    );
    fireEvent.change(screen.getByRole('textbox', { name: 'Component' }), {
      target: { value: 'frontend' },
    });
    expect(latest()).toEqual({
      units: [{ bindings: [{ component: 'frontend' }] }],
      other: 'retained',
    });
  });

  it('preserves boolean and numeric types through the real controls', () => {
    const shape: StructuredJsonSchema = {
      type: 'object',
      properties: {
        enabled: { type: 'boolean', title: 'Enabled' },
        port: { type: 'integer', title: 'Port', minimum: 1, maximum: 65535 },
      },
    };
    const { latest } = setup({ enabled: false, port: 8080 }, shape);
    fireEvent.click(screen.getByRole('switch'));
    expect(latest().enabled).toBe(true);
    fireEvent.change(screen.getByRole('textbox', { name: 'Port' }), {
      target: { value: '9090' },
    });
    expect(latest()).toEqual({ enabled: true, port: 9090 });
  });

  it('maps static boolean choices without string coercion', () => {
    const shape: StructuredJsonSchema = {
      type: 'object',
      properties: {
        mode: {
          type: 'boolean',
          title: 'Mode',
          enum: [false, true],
          'x-enumLabels': ['Off', 'On'],
        },
      },
    };
    const { latest } = setup({ mode: true }, shape);
    fireEvent.click(screen.getByTestId('select-trigger-draft.mode'));
    fireEvent.click(screen.getByRole('option', { name: 'Off' }));
    expect(latest().mode).toBe(false);
  });

  it('delegates reference choices to the original data-source control', () => {
    remote.requests.length = 0;
    const source = {
      type: 'api' as const,
      endpoint: '/api/dynamic/ab_team/list',
      valueField: 'pid',
      labelField: 'name',
    };
    const shape: StructuredJsonSchema = {
      type: 'object',
      properties: {
        team: { type: 'string', title: 'Team', 'x-dataSource': source },
      },
    };
    const { latest } = setup({}, shape);
    fireEvent.click(screen.getByRole('combobox', { name: 'Team' }));
    fireEvent.click(screen.getByRole('option', { name: 'Delivery team' }));
    expect(latest()).toEqual({ team: 'TEAM-OWNED' });
    expect(remote.requests).toContainEqual(source);
    expect(screen.queryByText('TEAM-OWNED')).toBeNull();
  });

  it('keeps read-only nested data visible without exposing mutation actions', () => {
    const { changes } = setup({ repositories: [{ name: 'Core' }] }, schema, { readOnly: true });
    expect(screen.getByRole('textbox', { name: /^Repository name\*?$/ })).toHaveAttribute(
      'readonly',
    );
    expect(screen.queryByRole('button', { name: 'Add Repositories' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Remove Repositories 1' })).toBeNull();
    expect(changes).toEqual([]);
  });

  it('disables fields and collection actions when the parent is disabled', () => {
    setup({ repositories: [{ name: 'Core' }] }, schema, { disabled: true });
    expect(screen.getByRole('textbox', { name: /^Repository name\*?$/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Add Repositories' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Remove Repositories 1' })).toBeDisabled();
  });

  it('fails visibly for unsupported configuration without raw-text fallback', () => {
    setup({}, { type: 'object', oneOf: [{ type: 'object' }] } as any);
    expect(screen.getByRole('alert')).toHaveTextContent('configuration is unavailable');
    expect(document.querySelector('textarea,input')).toBeNull();
  });

  it('localizes nested labels and placeholders using the original field controls', () => {
    setup(
      { name: 'Core' },
      {
        type: 'object',
        properties: {
          name: {
            type: 'string',
            'x-label': { en: 'Name', 'zh-CN': '名称' },
            'x-placeholder': { en: 'Enter name', 'zh-CN': '请输入名称' },
          },
        },
      },
      { context: { locale: 'zh-CN' } },
    );
    expect(screen.getByRole('textbox', { name: '名称' })).toHaveAttribute(
      'placeholder',
      '请输入名称',
    );
  });

  it('selects current sibling rows and rejects references after their source changes', () => {
    const shape: StructuredJsonSchema = {
      ...schema,
      properties: {
        ...schema.properties,
        selected: {
          type: 'string',
          title: 'Source',
          'x-optionsFrom': { collection: 'repositories', valueField: 'name', labelField: 'name' },
        },
      },
    };
    const { latest } = setup({ repositories: [{ name: 'Core' }, { name: 'Quote' }] }, shape);
    fireEvent.click(screen.getByTestId('select-trigger-draft.selected'));
    fireEvent.click(screen.getByRole('option', { name: 'Quote' }));
    expect(latest().selected).toBe('Quote');
    expect(structuredJsonError(latest(), shape)).toBeUndefined();
    fireEvent.change(screen.getAllByRole('textbox', { name: /^Repository name\*?$/ })[1], {
      target: { value: 'Quote renamed' },
    });
    expect(latest().selected).toBe('Quote'); // Never silently remap an existing reference.
    expect(structuredJsonError(latest(), shape)).toContain('Select Source again');
    fireEvent.click(screen.getByTestId('select-trigger-draft.selected'));
    fireEvent.click(screen.getByRole('option', { name: 'Quote renamed' }));
    expect(latest().selected).toBe('Quote renamed');
    expect(structuredJsonError(latest(), shape)).toBeUndefined();
    expect(
      structuredJsonError(
        { ...latest(), repositories: [{ name: 'Quote renamed' }, { name: 'Quote renamed' }] },
        shape,
      ),
    ).toContain('ambiguous');
  });

  it('does not repair malformed existing JSON on mount', () => {
    const change = vi.fn();
    render(
      <JsonEditor
        name="broken"
        mode="structured"
        structuredSchema={schema}
        value="{broken"
        onChange={change}
        context={{ locale: 'en' } as any}
      />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('invalid');
    expect(change).not.toHaveBeenCalled();
    expect(document.querySelector('textarea')).toBeNull();
  });
});

describe('Structured schema validation', () => {
  it('rejects nested missing values without echoing technical paths or input contents', () => {
    expect(structuredJsonError({ repositories: [{}] }, schema)).toBe(
      'Repository name is required.',
    );
    expect(structuredJsonError({ repositories: [{ name: 'Core' }] }, schema)).toBeUndefined();
  });

  it('uses conditional requirements while preserving the same immutable schema', () => {
    const shape: StructuredJsonSchema = {
      type: 'object',
      properties: {
        mode: { type: 'string', enum: ['source', 'import'] },
        artifact: { type: 'string', title: 'Artifact' },
      },
      if: { properties: { mode: { const: 'import' } }, required: ['mode'] },
      then: { required: ['artifact'] },
    };
    expect(structuredJsonError({ mode: 'source' }, shape)).toBeUndefined();
    expect(structuredJsonError({ mode: 'import' }, shape)).toBe('Artifact is required.');
    expect(shape.required).toBeUndefined();
  });

  it('rejects unsupported references, reserved fields and excessive shapes', () => {
    for (const shape of [
      { type: 'object', $ref: 'https://example.invalid/schema' },
      { type: 'object', properties: { constructor: { type: 'string' } } },
      { type: 'array', items: { type: 'string' }, maxItems: 101 },
      {
        type: 'object',
        properties: { team: { type: 'string', 'x-dataSource': { type: 'expression' } } },
      },
    ]) {
      expect(() => structuredJsonValidator(shape as any)).toThrow();
    }
  });

  it('rejects unsafe numeric precision and oversized input collections without truncation', () => {
    const shape: StructuredJsonSchema = {
      type: 'object',
      properties: { value: { type: 'integer', title: 'Count' } },
    };
    expect(structuredJsonError({ value: Number.MAX_SAFE_INTEGER + 1 }, shape)).toContain(
      'precision',
    );
    expect(
      structuredJsonError(
        { repositories: Array.from({ length: 101 }, () => ({ name: 'Core' })) },
        schema,
      ),
    ).toContain('size');
    const first = structuredJsonDefault({ type: 'array', default: [{ name: 'Core' }] }) as any[];
    const second = structuredJsonDefault({ type: 'array', default: [{ name: 'Core' }] });
    first[0].name = 'Changed';
    expect(second).toEqual([{ name: 'Core' }]);
  });
});
