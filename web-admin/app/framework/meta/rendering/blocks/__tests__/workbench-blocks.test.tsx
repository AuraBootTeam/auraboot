import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';

import { MetricStripBlockRenderer } from '../MetricStripBlockRenderer';
import { CandidateListBlockRenderer } from '../CandidateListBlockRenderer';
import { RecordInspectorBlockRenderer } from '../RecordInspectorBlockRenderer';
import { WorkbenchActionBarBlockRenderer } from '../WorkbenchActionBarBlockRenderer';

function makeRuntime(overrides: Partial<any> = {}): SchemaRuntime {
  const context: Record<string, any> = {
    locale: 'en-US',
    t: (k: string) => k,
    form: {},
    global: {},
    state: {},
  };
  const updateState = vi.fn((scopeId: string, key: string, value: any) => {
    context.state[key] = value;
  });
  const data = overrides.data ?? {};
  const reload = vi.fn().mockResolvedValue(undefined);
  const stub = {
    getContext: () => context,
    getEvaluator: () => ({
      evaluateCondition: (expr: string) => expr !== 'false',
      evaluateTemplate: (tpl: string) => tpl,
      evaluateObject: (obj: any) => obj,
    }),
    getDataSourceManager: () => ({
      getData: (id: string) => data[id],
      has: (id: string) => Object.prototype.hasOwnProperty.call(data, id),
      register: vi.fn(),
      reload,
    }),
    getStateManager: () => ({
      updateState,
      getContext: () => context,
    }),
    getScopeId: () => 'scope-1',
    getSchema: () => ({ id: 'test_schema', modelCode: 'test_model' }),
    __updateState: updateState,
    __reload: reload,
    ...overrides,
  };
  return stub as unknown as SchemaRuntime;
}

describe('MetricStripBlockRenderer', () => {
  it('renders a stable empty state when no metrics are configured', () => {
    const runtime = makeRuntime();
    const block: BlockConfig = {
      id: 'metrics',
      blockType: 'metric-strip',
      dataSource: 'summary',
      metrics: [],
    };

    render(<MetricStripBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByTestId('metric-strip-empty')).toHaveTextContent('No data');
  });

  it('renders the data source loading state before metric data arrives', () => {
    const runtime = makeRuntime({
      getDataSourceManager: () => ({
        getData: () => null,
        getState: () => ({ data: null, loading: true, error: null }),
        has: () => true,
        register: vi.fn(),
        reload: vi.fn(),
      }),
    });
    const block: BlockConfig = {
      id: 'metrics',
      blockType: 'metric-strip',
      dataSource: 'summary',
      metrics: [{ key: 'confirmed', label: 'Confirmed', valueField: 'confirmedCount' }],
    };

    render(<MetricStripBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByTestId('metric-strip-loading')).toHaveTextContent('Loading...');
  });

  it('renders the data source error state', () => {
    const runtime = makeRuntime({
      getDataSourceManager: () => ({
        getData: () => null,
        getState: () => ({
          data: null,
          loading: false,
          error: new Error('metrics failed'),
        }),
        has: () => true,
        register: vi.fn(),
        reload: vi.fn(),
      }),
    });
    const block: BlockConfig = {
      id: 'metrics',
      blockType: 'metric-strip',
      dataSource: 'summary',
      metrics: [{ key: 'confirmed', label: 'Confirmed', valueField: 'confirmedCount' }],
    };

    render(<MetricStripBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByRole('alert')).toHaveTextContent('metrics failed');
  });

  it('renders multiple metrics from one data source and writes state on click', () => {
    const runtime = makeRuntime({
      data: {
        summary: {
          confirmedCount: 2,
          pendingCount: 7,
          pendingText: 'manual review',
        },
      },
    }) as any;
    const block: BlockConfig = {
      id: 'metrics',
      blockType: 'metric-strip',
      dataSource: 'summary',
      metrics: [
        { key: 'confirmed', label: 'Confirmed', valueField: 'confirmedCount', tone: 'green' },
        {
          key: 'pending',
          label: 'Pending',
          valueField: 'pendingCount',
          subTextField: 'pendingText',
          tone: 'amber',
          onClick: {
            action: 'state.set',
            args: { lineFilter: { status: 'pending' } },
          },
        },
      ],
    };

    render(<MetricStripBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByText('Confirmed')).toBeInTheDocument();
    expect(screen.getByText('2')).toBeInTheDocument();
    expect(screen.getByText('Pending')).toBeInTheDocument();
    expect(screen.getByText('7')).toBeInTheDocument();
    expect(screen.getByText('manual review')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('metric-strip-item-pending'));

    expect(runtime.__updateState).toHaveBeenCalledWith('scope-1', 'lineFilter', {
      status: 'pending',
    });
  });

  it('rerenders when its data source publishes data after initial render', () => {
    const data: Record<string, any> = {
      summary: null,
    };
    const subscribers = new Set<(state: any) => void>();
    const runtime = makeRuntime({
      getDataSourceManager: () => ({
        getData: (id: string) => data[id],
        has: (id: string) => Object.prototype.hasOwnProperty.call(data, id),
        register: vi.fn(),
        reload: vi.fn(),
        subscribe: (_id: string, callback: (state: any) => void) => {
          subscribers.add(callback);
          return () => subscribers.delete(callback);
        },
      }),
    }) as any;
    const block: BlockConfig = {
      id: 'metrics',
      blockType: 'metric-strip',
      dataSource: 'summary',
      metrics: [
        { key: 'confirmed', label: 'Confirmed', valueField: 'confirmedCount', tone: 'green' },
      ],
    };

    render(<MetricStripBlockRenderer block={block} runtime={runtime} />);
    expect(screen.getByTestId('metric-strip-item-confirmed')).toHaveTextContent('-');

    act(() => {
      data.summary = { confirmedCount: 2 };
      subscribers.forEach((callback) =>
        callback({
          data: data.summary,
          loading: false,
          error: null,
          lastFetch: Date.now(),
        }),
      );
    });

    expect(screen.getByTestId('metric-strip-item-confirmed')).toHaveTextContent('2');
  });
});

describe('WorkbenchActionBarBlockRenderer', () => {
  it('renders configured actions and executes their lifecycle', async () => {
    const runtime = makeRuntime() as any;
    const block: BlockConfig = {
      id: 'actions',
      blockType: 'workbench-action-bar',
      actions: [
        {
          code: 'download_new_bom',
          label: 'Download New BOM',
          variant: 'primary',
          onClick: {
            action: 'dataSource.reload',
            args: { ids: ['summary', 'lines'] },
          },
        },
      ],
    };

    render(<WorkbenchActionBarBlockRenderer block={block} runtime={runtime} />);

    const button = screen.getByTestId('workbench-action-download_new_bom');
    expect(button).toHaveTextContent('Download New BOM');

    fireEvent.click(button);

    expect(runtime.__reload).toHaveBeenCalledWith(['summary', 'lines']);
  });
});

describe('CandidateListBlockRenderer', () => {
  it('renders a stable empty state when no candidates are available', () => {
    const runtime = makeRuntime({
      data: {
        candidates: [],
      },
    });
    const block: BlockConfig = {
      id: 'candidate_list',
      blockType: 'candidate-list',
      dataSource: 'candidates',
      item: {
        titleField: 'materialCode',
      },
    };

    render(<CandidateListBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByTestId('candidate-list-empty')).toBeInTheDocument();
  });

  it('writes selected candidate into runtime state', () => {
    const runtime = makeRuntime({
      data: {
        candidates: [
          {
            pid: 'C-1',
            materialCode: 'D410000098100',
            materialName: 'Resistor',
            spec: '62R 1% 0603',
            score: 98,
          },
        ],
      },
    }) as any;
    const block: BlockConfig = {
      id: 'candidate_list',
      blockType: 'candidate-list',
      dataSource: 'candidates',
      selection: { mode: 'single', bind: 'selectedCandidate' },
      item: {
        titleField: 'materialCode',
        subtitleField: 'materialName',
        descriptionField: 'spec',
        scoreField: 'score',
      },
    };

    render(<CandidateListBlockRenderer block={block} runtime={runtime} />);
    fireEvent.click(screen.getByTestId('candidate-list-item-C-1'));

    expect(runtime.__updateState).toHaveBeenCalledWith(
      'scope-1',
      'selectedCandidate',
      expect.objectContaining({ pid: 'C-1', materialCode: 'D410000098100' }),
    );
  });

  it('runs configured candidate actions after a candidate is selected', async () => {
    const runtime = makeRuntime({
      data: {
        candidates: [
          {
            pid: 'C-1',
            materialCode: 'D410000098100',
            materialName: 'Resistor',
          },
        ],
      },
    }) as any;
    const block: BlockConfig = {
      id: 'candidate_list',
      blockType: 'candidate-list',
      dataSource: 'candidates',
      selection: { mode: 'single', bind: 'selectedCandidate' },
      item: {
        titleField: 'materialCode',
        subtitleField: 'materialName',
      },
      actions: [
        {
          code: 'confirm',
          label: 'Confirm',
          onClick: {
            action: 'dataSource.reload',
            args: { ids: ['summary', 'lines'] },
          },
        },
      ],
    };

    render(<CandidateListBlockRenderer block={block} runtime={runtime} />);
    const actionButton = screen.getByTestId('candidate-list-action-confirm');
    expect(actionButton).toBeDisabled();

    fireEvent.click(screen.getByTestId('candidate-list-item-C-1'));
    expect(actionButton).not.toBeDisabled();
    fireEvent.click(actionButton);

    expect(runtime.__reload).toHaveBeenCalledWith(['summary', 'lines']);
  });
});

describe('RecordInspectorBlockRenderer', () => {
  it('renders an empty state when the configured context is missing', () => {
    const runtime = makeRuntime();
    const block: BlockConfig = {
      id: 'inspector',
      blockType: 'record-inspector',
      context: '${state.selectedLine}',
      empty: { title: 'Select a row' },
      blocks: [],
    };

    render(<RecordInspectorBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByText('Select a row')).toBeInTheDocument();
  });

  it('renders configured fields from the selected context record', () => {
    const runtime = makeRuntime({
      getContext: () => ({
        locale: 'en-US',
        t: (k: string) => k,
        form: {},
        global: {},
        state: {
          selectedLine: {
            materialCode: 'D410000098100',
            spec: '62R 1% 0603',
          },
        },
      }),
    });
    const block: BlockConfig = {
      id: 'inspector',
      blockType: 'record-inspector',
      context: '${state.selectedLine}',
      fields: [
        { field: 'materialCode', label: 'Material Code' },
        { field: 'spec', label: 'Spec', span: 2 },
      ],
    };

    render(<RecordInspectorBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByText('Material Code')).toBeInTheDocument();
    expect(screen.getByText('D410000098100')).toBeInTheDocument();
    expect(screen.getByText('Spec')).toBeInTheDocument();
    expect(screen.getByText('62R 1% 0603')).toBeInTheDocument();
  });

  it('rerenders when runtime state selects a record after initial empty render', () => {
    const context: Record<string, any> = {
      locale: 'en-US',
      t: (k: string) => k,
      form: {},
      global: {},
      state: {},
    };
    const subscribers = new Set<() => void>();
    const runtime = makeRuntime({
      getContext: () => context,
      getStateManager: () => ({
        getStore: () => ({
          subscribe: (callback: () => void) => {
            subscribers.add(callback);
            return () => subscribers.delete(callback);
          },
        }),
      }),
      getScopeId: () => 'scope-1',
    });
    const block: BlockConfig = {
      id: 'inspector',
      blockType: 'record-inspector',
      context: '${state.selectedLine}',
      empty: { title: 'Select a row' },
      fields: [{ field: 'materialCode', label: 'Material Code' }],
    };

    render(<RecordInspectorBlockRenderer block={block} runtime={runtime} />);
    expect(screen.getByTestId('record-inspector-empty')).toHaveTextContent('Select a row');

    act(() => {
      context.state.selectedLine = { materialCode: 'D410000098100' };
      subscribers.forEach((callback) => callback());
    });

    expect(screen.getByTestId('record-inspector')).toHaveTextContent('D410000098100');
  });
});
