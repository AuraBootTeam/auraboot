import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ListConfigPanel } from '../ListConfigPanel';
import type { PageSchema } from '~/plugins/core-designer/components/studio/domain/dsl/types';
import type {
  ModelCapabilities,
  UseModelCapabilitiesResult,
} from '~/shared/hooks/useModelCapabilities';

const capabilitiesData = {
  list: true,
  detail: true,
  sort: true,
  filter: true,
  paginate: true,
  export: true,
  create: false,
  update: false,
  delete: false,
  bulkDelete: false,
  sortableFields: ['name', 'createdAt'],
  filterableFields: ['name', 'status'],
} as ModelCapabilities;

let mockedCapabilitiesResult: UseModelCapabilitiesResult = {
  data: capabilitiesData,
  loading: false,
  error: undefined,
  refetch: vi.fn(),
};

vi.mock('~/shared/hooks/useModelCapabilities', () => ({
  useModelCapabilities: () => mockedCapabilitiesResult,
}));

function baseSchema(): PageSchema {
  return {
    schemaVersion: 2,
    kind: 'list',
    modelCode: 'test_model',
    blocks: [],
    layout: { type: 'stack' },
    profile: 'admin',
    title: { zh: 'test' },
  } as unknown as PageSchema;
}

describe('ListConfigPanel', () => {
  beforeEach(() => {
    mockedCapabilitiesResult = {
      data: capabilitiesData,
      loading: false,
      error: undefined,
      refetch: vi.fn(),
    };
  });

  it('renders with an empty schema and shows the columns tab by default', async () => {
    render(<ListConfigPanel schema={baseSchema()} onSchemaChange={() => {}} />);
    expect(screen.getByTestId('list-config-panel')).toBeInTheDocument();
    expect(screen.getByTestId('list-designer-summary')).toBeInTheDocument();
    expect(screen.getByTestId('list-designer-workspace')).toBeInTheDocument();
    expect(screen.getByTestId('list-preview-pane')).toBeInTheDocument();
    expect(screen.getByTestId('list-tab-columns')).toBeInTheDocument();
    expect(screen.getByTestId('list-tab-filters')).toBeInTheDocument();
    expect(screen.getByTestId('list-tab-toolbar')).toBeInTheDocument();
    expect(screen.getByTestId('list-tab-behavior')).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByTestId('columns-tab')).toBeInTheDocument();
    });
    expect(screen.getByText('列表设计')).toBeInTheDocument();
    expect(screen.getByText('实时预览')).toBeInTheDocument();
  });

  it('switches to the filters tab on click and enforces the filterable whitelist', async () => {
    render(<ListConfigPanel schema={baseSchema()} onSchemaChange={() => {}} />);
    await waitFor(() => {
      expect(screen.getByTestId('columns-tab')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('list-tab-filters'));
    expect(screen.getByTestId('filters-tab')).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByTestId('filter-toggle-name')).toBeInTheDocument();
    });

    // filterable whitelist = ['name', 'status'] → createdAt must NOT be shown
    expect(screen.getByTestId('filter-toggle-status')).toBeInTheDocument();
    expect(screen.queryByTestId('filter-toggle-createdAt')).toBeNull();
  });

  it('toolbar presets are gated by capabilities', async () => {
    render(<ListConfigPanel schema={baseSchema()} onSchemaChange={() => {}} />);
    await waitFor(() => {
      expect(screen.getByTestId('columns-tab')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('list-tab-toolbar'));
    expect(screen.getByTestId('toolbar-tab')).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByTestId('toolbar-preset-create')).toBeInTheDocument();
    });

    // create / bulkDelete are false in capabilities → disabled; export = true → enabled.
    // refresh is a page-level read action and does not depend on model mutation capabilities.
    expect(screen.getByTestId('toolbar-preset-create')).toBeDisabled();
    expect(screen.getByTestId('toolbar-preset-bulkDelete')).toBeDisabled();
    expect(screen.getByTestId('toolbar-preset-export')).not.toBeDisabled();
    expect(screen.getByTestId('toolbar-preset-refresh')).not.toBeDisabled();
  });

  it('renders the shared icon picker for toolbar custom buttons', async () => {
    render(<ListConfigPanel schema={baseSchema()} onSchemaChange={() => {}} />);
    await waitFor(() => {
      expect(screen.getByTestId('columns-tab')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('list-tab-toolbar'));
    await waitFor(() => {
      expect(screen.getByTestId('toolbar-tab')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('toolbar-add-custom-button'));

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '选择图标' })).toBeInTheDocument();
    });
  });

  it('edits custom refresh action fields and emits targeted data source flow action', async () => {
    const onChange = vi.fn();
    render(<ListConfigPanel schema={baseSchema()} onSchemaChange={onChange} />);
    await waitFor(() => {
      expect(screen.getByTestId('columns-tab')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('list-tab-toolbar'));
    await waitFor(() => {
      expect(screen.getByTestId('toolbar-tab')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByTestId('toolbar-add-custom-button'));

    await waitFor(() => {
      expect(screen.getByTestId('schema-config-field-code')).toBeInTheDocument();
    });
    const fieldTextbox = (key: string) => {
      const input = screen
        .getByTestId(`schema-config-field-${key}`)
        .querySelector('[role="textbox"], input, textarea');
      if (!input) throw new Error(`Missing textbox for ${key}`);
      return input as HTMLElement;
    };
    const actionKindSelect = screen
      .getByTestId('schema-config-field-actionKind')
      .querySelector('[role="combobox"]');
    if (!actionKindSelect) throw new Error('Missing actionKind combobox');

    fireEvent.change(fieldTextbox('label'), { target: { value: 'Refresh orders' } });
    fireEvent.change(fieldTextbox('code'), { target: { value: 'refresh_orders' } });
    fireEvent.click(actionKindSelect);
    fireEvent.click(await screen.findByText('刷新数据源'));
    await waitFor(() => {
      expect(screen.getByTestId('schema-config-field-targetDataSource')).toBeInTheDocument();
    });
    fireEvent.change(fieldTextbox('targetDataSource'), { target: { value: 'ds_orders' } });

    await waitFor(() => {
      const latest = onChange.mock.calls[onChange.mock.calls.length - 1]?.[0];
      const toolbar = latest?.blocks?.find((block: { blockType: string }) => block.blockType === 'toolbar');
      expect(toolbar?.buttons?.[0]).toEqual({
        code: 'refresh_orders',
        label: 'Refresh orders',
        action: {
          type: 'flow',
          steps: [{ action: 'dataSource.reload', args: { target: 'ds_orders' } }],
        },
        events: {
          onClick: { action: 'dataSource.reload', args: { target: 'ds_orders' } },
        },
      });
    });
  });

  it('propagates VM changes to schema.blocks', async () => {
    const onChange = vi.fn();
    render(<ListConfigPanel schema={baseSchema()} onSchemaChange={onChange} />);
    await waitFor(() => {
      expect(screen.getByTestId('columns-tab')).toBeInTheDocument();
    });
    // Initial mount pushes the canonical 3-block shape outward.
    await waitFor(() => {
      expect(onChange).toHaveBeenCalled();
    });
    const latest = onChange.mock.calls[onChange.mock.calls.length - 1][0];
    expect(Array.isArray(latest.blocks)).toBe(true);
    expect(latest.blocks.map((b: { blockType: string }) => b.blockType)).toEqual([
      'filters',
      'toolbar',
      'table',
    ]);
  });

  it('falls back to schema fields when model capabilities fail to load', async () => {
    mockedCapabilitiesResult = {
      data: undefined,
      loading: false,
      error: new Error('Model not found: tenant'),
      refetch: vi.fn(),
    };

    const schema = {
      ...baseSchema(),
      modelCode: 'tenant',
      blocks: [
        { blockType: 'filters', fields: ['name'] },
        { blockType: 'toolbar', buttons: [{ preset: 'create' }] },
        {
          blockType: 'table',
          columns: [{ field: 'name', width: 180 }, 'createdAt'],
          props: { defaultSortField: 'createdAt' },
        },
      ],
    } as unknown as PageSchema;

    render(<ListConfigPanel schema={schema} onSchemaChange={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('capability-fallback-banner')).toBeInTheDocument();
    });
    expect(screen.getByText('模型能力读取失败')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('list-tab-columns'));
    await waitFor(() => {
      expect(screen.getByTestId('column-toggle-name')).toBeInTheDocument();
    });
    expect(screen.getByTestId('column-toggle-createdAt')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('list-tab-filters'));
    await waitFor(() => {
      expect(screen.getByTestId('filter-toggle-name')).toBeInTheDocument();
    });
    expect(screen.queryByTestId('filter-toggle-createdAt')).toBeInTheDocument();
  });
});
