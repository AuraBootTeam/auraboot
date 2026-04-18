import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ListConfigPanel } from '../ListConfigPanel';
import type { PageSchema } from '~/plugins/core-designer/components/studio/domain/dsl/types';

const capabilitiesResponse = {
  data: {
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
  },
};

function mockFetchOk() {
  globalThis.fetch = vi.fn().mockResolvedValue({
    ok: true,
    json: async () => capabilitiesResponse,
  }) as unknown as typeof fetch;
}

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
    mockFetchOk();
  });

  it('renders with an empty schema and shows the columns tab by default', async () => {
    render(<ListConfigPanel schema={baseSchema()} onSchemaChange={() => {}} />);
    expect(screen.getByTestId('list-config-panel')).toBeInTheDocument();
    expect(screen.getByTestId('list-tab-columns')).toBeInTheDocument();
    expect(screen.getByTestId('list-tab-filters')).toBeInTheDocument();
    expect(screen.getByTestId('list-tab-toolbar')).toBeInTheDocument();
    expect(screen.getByTestId('list-tab-behavior')).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByTestId('columns-tab')).toBeInTheDocument();
    });
  });

  it('switches to the filters tab on click and enforces the filterable whitelist', async () => {
    render(<ListConfigPanel schema={baseSchema()} onSchemaChange={() => {}} />);
    await waitFor(() => {
      expect(screen.getByTestId('columns-tab')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('list-tab-filters'));
    expect(screen.getByTestId('filters-tab')).toBeInTheDocument();

    // filterable whitelist = ['name', 'status'] → createdAt must NOT be shown
    expect(screen.getByTestId('filter-toggle-name')).toBeInTheDocument();
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

    // create / bulkDelete are false in capabilities → disabled; export = true → enabled.
    expect(screen.getByTestId('toolbar-preset-create')).toBeDisabled();
    expect(screen.getByTestId('toolbar-preset-bulkDelete')).toBeDisabled();
    expect(screen.getByTestId('toolbar-preset-export')).not.toBeDisabled();
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
});
