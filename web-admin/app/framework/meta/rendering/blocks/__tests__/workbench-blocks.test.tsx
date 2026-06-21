import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { evaluateCondition as evaluateExpressionCondition } from '~/framework/meta/runtime/expression/evaluator';

import { MetricStripBlockRenderer } from '../MetricStripBlockRenderer';
import { CandidateListBlockRenderer } from '../CandidateListBlockRenderer';
import { RecordInspectorBlockRenderer } from '../RecordInspectorBlockRenderer';
import { WorkbenchActionBarBlockRenderer } from '../WorkbenchActionBarBlockRenderer';
import { EvidencePanelBlockRenderer } from '../EvidencePanelBlockRenderer';
import { ArtifactTimelineBlockRenderer } from '../ArtifactTimelineBlockRenderer';
import { ReviewDrawerBlockRenderer } from '../ReviewDrawerBlockRenderer';
import { StatusBannerBlockRenderer } from '../StatusBannerBlockRenderer';
import { useRuntimeStateSubscription } from '../workbenchBlockUtils';
import { fetchResult } from '~/shared/services/http-client';

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: vi.fn(() => Promise.resolve({ code: '0', data: {} })),
}));

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
      evaluateCondition: (expr: string, expressionContext = context) =>
        evaluateExpressionCondition(expr, expressionContext as any),
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

  it('renders localized metric subText without leaking object text', () => {
    const runtime = makeRuntime({
      data: {
        summary: {
          confirmedCount: 3,
        },
      },
    }) as any;
    const block: BlockConfig = {
      id: 'metrics',
      blockType: 'metric-strip',
      dataSource: 'summary',
      metrics: [
        {
          key: 'confirmed',
          label: 'Confirmed',
          valueField: 'confirmedCount',
          subText: {
            'zh-CN': '含 MOQ/SPQ/币种/有效期',
            'en-US': 'Includes MOQ/SPQ/currency/validity',
          },
        },
      ],
    };

    render(<MetricStripBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByTestId('metric-strip-item-confirmed')).toHaveTextContent(
      'Includes MOQ/SPQ/currency/validity',
    );
    expect(screen.queryByText('[object Object]')).toBeNull();
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

  it('renders compact chips and reads values from JSON string fields', () => {
    const runtime = makeRuntime({
      data: {
        summary: {
          reasonBreakdown: '{"match_multi_candidate":48,"unrecognized_category":1}',
        },
      },
    }) as any;
    const block: BlockConfig = {
      id: 'reason_filters',
      blockType: 'metric-strip',
      dataSource: 'summary',
      variant: 'chips',
      title: 'Reason Breakdown',
      metrics: [
        {
          key: 'multi',
          label: 'Multiple Candidates',
          valueField: 'reasonBreakdown.match_multi_candidate',
          onClick: {
            action: 'state.set',
            args: { reasonFilterCodes: ['match_multi_candidate'] },
          },
        },
      ],
    };

    render(<MetricStripBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByTestId('metric-strip-reason_filters')).toHaveTextContent('Reason Breakdown');
    expect(screen.getByTestId('metric-strip-item-multi')).toHaveTextContent('48');
    fireEvent.click(screen.getByTestId('metric-strip-item-multi'));
    expect(runtime.__updateState).toHaveBeenCalledWith('scope-1', 'reasonFilterCodes', [
      'match_multi_candidate',
    ]);
  });

  it('hides conditional chip metrics until their visibleWhen state is true', () => {
    const runtime = makeRuntime({
      data: {
        summary: {
          reasonBreakdown: {
            match_multi_candidate: 48,
          },
        },
      },
    }) as any;
    const block: BlockConfig = {
      id: 'reason_filters',
      blockType: 'metric-strip',
      dataSource: 'summary',
      variant: 'chips',
      metrics: [
        {
          key: 'multi',
          label: 'Multiple Candidates',
          valueField: 'reasonBreakdown.match_multi_candidate',
        },
        {
          key: 'clear',
          label: 'Clear Filter',
          value: 'x',
          align: 'end',
          visibleWhen: 'state.reasonFilterCodes != null && state.reasonFilterCodes.length > 0',
        },
      ],
    };

    const { rerender } = render(<MetricStripBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByTestId('metric-strip-item-multi')).toBeVisible();
    expect(screen.queryByTestId('metric-strip-item-clear')).toBeNull();

    runtime.getContext().state.reasonFilterCodes = ['match_multi_candidate'];
    rerender(<MetricStripBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByTestId('metric-strip-item-clear')).toHaveClass('ml-auto');
  });

  it('maps boolean metric values to configured display text', () => {
    const runtime = makeRuntime({
      data: {
        summary: {
          dirty: true,
        },
      },
    }) as any;
    const block: BlockConfig = {
      id: 'metrics',
      blockType: 'metric-strip',
      dataSource: 'summary',
      metrics: [
        {
          key: 'dirty',
          label: 'Export State',
          valueField: 'dirty',
          valueMap: {
            true: 'Dirty',
            false: 'Synced',
          },
        },
      ],
    };

    render(<MetricStripBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByTestId('metric-strip-item-dirty')).toHaveTextContent('Dirty');
  });

  it('keeps card metrics fixed-size when a value is long', () => {
    const runtime = makeRuntime({
      data: {
        summary: {
          feeAmount: 0,
          ruleFile:
            'Jiejia-PCBA-process-fee-pricing-rule-V1.3-20260126-updated-copy-with-a-very-long-name.xls',
          importedAt: '2026-06-15 10:23:03.589449+00',
        },
      },
    }) as any;
    const block: BlockConfig = {
      id: 'process_fee_metrics',
      blockType: 'metric-strip',
      dataSource: 'summary',
      metrics: [
        { key: 'fee', label: 'Process Fee', valueField: 'feeAmount', tone: 'blue' },
        {
          key: 'rule_file',
          label: 'Rule Excel',
          valueField: 'ruleFile',
          subTextField: 'importedAt',
        },
      ],
    };

    render(<MetricStripBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByTestId('metric-strip')).toHaveClass('items-stretch');
    expect(screen.getByTestId('metric-strip-item-fee')).toHaveClass('h-28', 'overflow-hidden');
    expect(screen.getByTestId('metric-strip-item-rule_file')).toHaveClass(
      'h-28',
      'overflow-hidden',
    );
    expect(screen.getByTestId('metric-strip-value-rule_file')).toHaveClass(
      'min-w-0',
      'truncate',
    );
    expect(screen.getByTestId('metric-strip-subtext-rule_file')).toHaveClass('line-clamp-2');
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

  it('supports bare compact action bars for workbench headers', () => {
    const runtime = makeRuntime() as any;
    const block: BlockConfig = {
      id: 'actions',
      blockType: 'workbench-action-bar',
      surface: 'bare',
      density: 'compact',
      actions: [
        {
          code: 'download',
          label: 'Download',
          variant: 'primary',
        },
      ],
    };

    render(<WorkbenchActionBarBlockRenderer block={block} runtime={runtime} />);

    const bar = screen.getByTestId('workbench-action-bar');
    expect(bar).not.toHaveClass('border');
    expect(screen.getByTestId('workbench-action-download')).toHaveClass('text-xs');
  });

  it('renders an optional section title beside the action group', () => {
    const runtime = makeRuntime() as any;
    const block: BlockConfig = {
      id: 'actions',
      blockType: 'workbench-action-bar',
      title: 'Price Waterfall',
      surface: 'bare',
      actions: [
        {
          code: 'run_sourcing',
          label: 'Run Sourcing',
          variant: 'primary',
        },
      ],
    };

    render(<WorkbenchActionBarBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByRole('heading', { name: 'Price Waterfall' })).toBeInTheDocument();
    expect(screen.getByTestId('workbench-action-run_sourcing')).toHaveTextContent('Run Sourcing');
  });
});

describe('StatusBannerBlockRenderer', () => {
  it('renders a running task banner and polls configured data sources', () => {
    vi.useFakeTimers();
    const reload = vi.fn().mockResolvedValue(undefined);
    const runtime = makeRuntime({
      getDataSourceManager: () => ({
        getData: () => ({
          bom_task_status: 'parsing',
          bom_task_raw_filename: 'raw.xlsx',
          bom_task_total_rows: 88,
        }),
        getState: () => ({
          data: {
            bom_task_status: 'parsing',
            bom_task_raw_filename: 'raw.xlsx',
            bom_task_total_rows: 88,
          },
          loading: false,
          error: null,
        }),
        has: () => true,
        register: vi.fn(),
        reload,
        subscribe: vi.fn(() => () => undefined),
      }),
    }) as any;
    const block: BlockConfig = {
      id: 'task_status',
      blockType: 'status-banner',
      dataSource: 'summary',
      statusField: 'bom_task_status',
      hideStatuses: ['completed'],
      failedStatuses: ['failed'],
      titleMap: {
        parsing: 'Parsing BOM',
      },
      descriptionMap: {
        parsing: 'Parsing ${record.bom_task_raw_filename}; the page will refresh automatically.',
      },
      summaryFields: [{ key: 'total', label: 'Total Rows', field: 'bom_task_total_rows' }],
      poll: {
        enabledWhenStatuses: ['parsing'],
        intervalMs: 3000,
        reload: ['summary', 'lines'],
      },
    };

    render(<StatusBannerBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByTestId('status-banner-task_status')).toHaveTextContent('Parsing BOM');
    expect(screen.getByTestId('status-banner-task_status')).toHaveTextContent('raw.xlsx');
    expect(screen.getByTestId('status-banner-task_status')).toHaveTextContent('Total Rows');
    expect(screen.getByTestId('status-banner-task_status')).toHaveTextContent('88');

    act(() => {
      vi.advanceTimersByTime(3000);
    });

    expect(reload).toHaveBeenCalledWith(['summary', 'lines']);
    vi.useRealTimers();
  });

  it('keeps long summary values inside their grid cells', () => {
    const longCustomerName = 'Golden SmartHub_MAIN_REV1.3_Design_MFG';
    const runtime = makeRuntime({
      getDataSourceManager: () => ({
        getData: () => ({
          quote_status: 'draft',
          quote_customer: longCustomerName,
        }),
        getState: () => ({
          data: {
            quote_status: 'draft',
            quote_customer: longCustomerName,
          },
          loading: false,
          error: null,
        }),
        has: () => true,
        register: vi.fn(),
        reload: vi.fn(),
        subscribe: vi.fn(() => () => undefined),
      }),
    }) as any;
    const block: BlockConfig = {
      id: 'quote_status',
      blockType: 'status-banner',
      dataSource: 'summary',
      statusField: 'quote_status',
      titleMap: {
        draft: 'Draft',
      },
      summaryFields: [{ key: 'customer', label: 'Customer', field: 'quote_customer' }],
    };

    render(<StatusBannerBlockRenderer block={block} runtime={runtime} />);

    const value = screen.getByText(longCustomerName);
    expect(value).toHaveClass('break-words');
    expect(value).toHaveAttribute('title', longCustomerName);
    expect(value.closest('div')).toHaveClass('min-w-0');
  });

  it('renders linked summary values and skips system pid fields', () => {
    const quoteRecord = {
      qo_quote_status: 'draft',
      qo_quote_customer: 'Golden SmartHub_MAIN_REV1.3_Design_MFG',
      qo_quote_crm_account_id: '01KV1FYEZC514WPMAFHYNMGRQM',
      qo_quote_customer_request_id: '01KV1FYF0SN7N2H5FVMJNJKGEN',
      pcba_rfq_url: '/p/crm_customer_request_pcba_rfq/view/01KV1FYF1ZMFFAQX08Y7REXAQ0',
      pid: '01KV1FYF344J67GYF96ZJQEZDS',
    };
    const runtime = makeRuntime({
      getDataSourceManager: () => ({
        getData: () => quoteRecord,
        getState: () => ({ data: quoteRecord, loading: false, error: null }),
        has: () => true,
        register: vi.fn(),
        reload: vi.fn(),
        subscribe: vi.fn(() => () => undefined),
      }),
    }) as any;
    const block: BlockConfig = {
      id: 'quote_status',
      blockType: 'status-banner',
      dataSource: 'summary',
      statusField: 'qo_quote_status',
      titleMap: {
        draft: 'Draft',
      },
      summaryFields: [
        {
          key: 'customer',
          label: 'Customer',
          field: 'qo_quote_customer',
          linkTo: '/p/crm_account_common/view/${record.qo_quote_crm_account_id}',
        },
        {
          key: 'customerRequest',
          label: 'Customer Request',
          field: 'qo_quote_customer_request_id',
          linkField: ['missing_url', 'pcba_rfq_url'],
        },
        { key: 'pid', label: 'PID', field: 'pid' },
      ],
    };

    render(<StatusBannerBlockRenderer block={block} runtime={runtime} />);

    const customerLink = screen.getByRole('link', {
      name: 'Golden SmartHub_MAIN_REV1.3_Design_MFG',
    });
    expect(customerLink).toHaveAttribute(
      'href',
      '/p/crm_account_common/view/01KV1FYEZC514WPMAFHYNMGRQM',
    );
    expect(customerLink).toHaveClass('text-accent', 'underline');
    expect(screen.getByRole('link', { name: '01KV1FYF0SN7N2H5FVMJNJKGEN' })).toHaveAttribute(
      'href',
      '/p/crm_customer_request_pcba_rfq/view/01KV1FYF1ZMFFAQX08Y7REXAQ0',
    );
    expect(screen.queryByText('01KV1FYF344J67GYF96ZJQEZDS')).not.toBeInTheDocument();
  });

  it('hides when the current status is configured as hidden', () => {
    const runtime = makeRuntime({
      getDataSourceManager: () => ({
        getData: () => ({ bom_task_status: 'completed' }),
        getState: () => ({ data: { bom_task_status: 'completed' }, loading: false, error: null }),
        has: () => true,
        register: vi.fn(),
        reload: vi.fn(),
        subscribe: vi.fn(() => () => undefined),
      }),
    }) as any;
    const block: BlockConfig = {
      id: 'task_status',
      blockType: 'status-banner',
      dataSource: 'summary',
      statusField: 'bom_task_status',
      hideStatuses: ['completed'],
    };

    render(<StatusBannerBlockRenderer block={block} runtime={runtime} />);

    expect(screen.queryByTestId('status-banner-task_status')).not.toBeInTheDocument();
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
        rawItems: [
          {
            pid: 'RAW-1',
            bom_raw_row_no: 11,
            bom_raw_extra_columns_json:
              '{"__parse_evidence":{"profileCode":"JIEJIA_WB_FLEX_MAIN_V1","composition":{"match":"Description + Value + Footprint"},"llm":{"confidence":0.88}}}',
          },
        ],
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

  it('renders configured candidate detail fields from a JSON snapshot', () => {
    const runtime = makeRuntime({
      data: {
        candidates: [
          {
            pid: 'C-1',
            materialCode: 'D410000098100',
            snapshot: '{"materialName":"Resistor","spec":"62R 1% 0603","brand":"YAGEO"}',
          },
        ],
      },
    }) as any;
    const block: BlockConfig = {
      id: 'candidate_list',
      blockType: 'candidate-list',
      dataSource: 'candidates',
      item: {
        titleField: 'materialCode',
        descriptionField: 'snapshot',
        detailFields: [
          { key: 'name', label: 'Material Name', sourceField: 'snapshot', field: 'materialName' },
          { key: 'spec', label: 'Spec', sourceField: 'snapshot', field: 'spec', span: 2 },
          { key: 'brand', label: 'Brand', sourceField: 'snapshot', field: 'brand' },
        ],
      },
    };

    render(<CandidateListBlockRenderer block={block} runtime={runtime} />);

    const candidate = screen.getByTestId('candidate-list-item-C-1');
    expect(candidate).toHaveTextContent('Material Name');
    expect(candidate).toHaveTextContent('Resistor');
    expect(candidate).toHaveTextContent('62R 1% 0603');
    expect(candidate).toHaveTextContent('YAGEO');
    expect(candidate).not.toHaveTextContent('{"materialName"');
  });

  it('constrains long candidate lists when maxHeight is configured', () => {
    const runtime = makeRuntime({
      data: {
        candidates: [{ pid: 'C-1', materialCode: 'D410000098100' }],
      },
    }) as any;
    const block: BlockConfig = {
      id: 'candidate_list',
      blockType: 'candidate-list',
      dataSource: 'candidates',
      maxHeight: 480,
      item: {
        titleField: 'materialCode',
      },
    };

    render(<CandidateListBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByTestId('candidate-list')).toHaveClass('min-h-0');
    expect(screen.getByTestId('candidate-list')).toHaveStyle({ maxHeight: '480px' });
  });
});

describe('ReviewDrawerBlockRenderer', () => {
  const selectedLine: Record<string, unknown> = {
    pid: 'std-1',
    bom_std_row_no: 5,
    bom_std_raw_row_no: 11,
    bom_std_refdes: 'LED1',
    bom_std_status_label: '待确认',
    bom_std_material_code: '',
    bom_std_material_name: '贴片 LED',
    bom_std_spec: '绿色 0603 高亮 20mA + BL-HG034A-TRB',
    bom_std_qty: 1,
    bom_std_reason_code: 'match_multi_candidate',
    bom_std_parse_confidence: 92,
    bom_std_source_hash: 'a9f3',
    bom_std_profile_score: 96,
    bom_std_llm_mode: 'field_parse',
    bom_std_llm_translation_policy: 'none',
    bom_std_parse_json: '{"profileCode":"JIEJIA_WB_FLEX_MAIN_V1","llm":{"mode":"field_parse"}}',
  };

  function makeReviewDrawerRuntime(line: Record<string, unknown> = selectedLine) {
    return makeRuntime({
      data: {
        rawItems: [
          {
            pid: 'raw-1',
            bom_raw_row_no: 11,
            bom_raw_extra_columns_json: JSON.stringify({
              __parse_evidence: {
                profileCode: 'JIEJIA_WB_FLEX_MAIN_V1',
                composition: {
                  matchRule: 'Description + Value + Footprint',
                },
                llm: {
                  confidence: 0.88,
                },
              },
            }),
          },
        ],
        candidates: [
          {
            pid: 'ME-1',
            bom_me_material_code: 'D410000006100',
            bom_me_score: 92,
            bom_me_candidate_snapshot_json:
              '{"materialCode":"D410000006100","materialName":"贴片电阻","specModel":"贴片电阻 240Ω ±1% 0201","brand":"","mpn":"","packageCode":"0201","attributes":{"resistance":"240Ω","tolerance_pct":0.01}}',
            bom_me_evidence_json: '{"matchSource":"mpn_exact"}',
          },
        ],
        exports: [
          {
            pid: 'export-1',
            bom_er_revision_no: 3,
            bom_er_filename: 'standard-bom.xlsx',
            bom_er_status: 'dirty',
          },
        ],
      },
      getContext: () => ({
        locale: 'zh-CN',
        t: (k: string) => k,
        form: { pid: 'task-1' },
        global: {},
        state: { selectedBomLine: line },
      }),
    }) as any;
  }

  const reviewDrawerBlock: BlockConfig = {
    id: 'review_drawer',
    blockType: 'review-drawer',
    context: '${state.selectedBomLine}',
    titleTemplate:
      'Row ${record.bom_std_row_no} · ${record.bom_std_refdes} · ${record.bom_std_status_label}',
    summaryBadges: [
      {
        key: 'profile',
        label: 'Profile',
        valueField: 'bom_std_profile_score',
        unit: '%',
        tone: 'blue',
      },
      {
        key: 'parse',
        label: '解析',
        valueField: 'bom_std_parse_confidence',
        unit: '%',
        tone: 'green',
      },
      { key: 'llm', label: 'LLM', valueField: 'bom_std_llm_mode', tone: 'purple' },
    ],
    compare: {
      rawTitle: '原始 BOM',
      canonicalTitle: 'Canonical Line',
      rawFields: [
        { key: 'refdes', label: 'Designator', field: 'bom_std_refdes' },
        { key: 'qty', label: 'Qty', field: 'bom_std_qty' },
        { key: 'sourceHash', label: 'source_hash', field: 'bom_std_source_hash' },
      ],
      canonicalFields: [
        { key: 'name', label: '物料名称', field: 'bom_std_material_name' },
        { key: 'spec', label: 'match_spec', field: 'bom_std_spec' },
        {
          key: 'code',
          label: '标准编码',
          field: 'bom_std_material_code',
          emptyText: '待确认候选后写入',
        },
      ],
    },
    source: {
      record: {
        dataSource: 'rawItems',
        matchField: 'bom_raw_row_no',
        recordField: 'bom_std_raw_row_no',
      },
      summary: {
        items: [
          {
            key: 'profile',
            label: 'Profile',
            sourceField: 'bom_raw_extra_columns_json',
            field: '__parse_evidence.profileCode',
          },
          {
            key: 'composition',
            label: '字段融合',
            sourceField: 'bom_raw_extra_columns_json',
            field: '__parse_evidence.composition.matchRule',
          },
          {
            key: 'llm',
            label: 'LLM',
            sourceField: 'bom_raw_extra_columns_json',
            field: '__parse_evidence.llm.confidence',
            emptyText: '未调用',
          },
        ],
      },
      cards: [
        {
          key: 'profile',
          title: 'Profile Detector',
          sourceField: 'bom_raw_extra_columns_json',
          field: '__parse_evidence.profileCode',
        },
        {
          key: 'composition',
          title: '字段融合规则',
          sourceField: 'bom_raw_extra_columns_json',
          field: '__parse_evidence.composition.matchRule',
        },
        {
          key: 'llm',
          title: 'LLM 辅助',
          sourceField: 'bom_raw_extra_columns_json',
          field: '__parse_evidence.llm.confidence',
        },
      ],
      policies: [
        { key: 'allowed', title: '允许行为', items: ['extract_fields', 'compose_match_spec'] },
        {
          key: 'forbidden',
          title: '禁止行为',
          items: ['generate_material_code', 'auto_select_candidate'],
        },
      ],
      jsonField: 'bom_raw_extra_columns_json',
    },
    candidates: {
      dataSource: 'candidates',
      selection: { bind: 'selectedCandidate' },
      item: {
        titleField: 'bom_me_material_code',
        scoreField: 'bom_me_score',
        detailFields: [
          {
            key: 'name',
            label: '物料名称',
            sourceField: 'bom_me_candidate_snapshot_json',
            field: 'materialName',
          },
          {
            key: 'spec',
            label: '规格',
            sourceField: 'bom_me_candidate_snapshot_json',
            field: 'specModel',
          },
          {
            key: 'package',
            label: '封装',
            sourceField: 'bom_me_candidate_snapshot_json',
            field: 'packageCode',
          },
          {
            key: 'resistance',
            label: '阻值',
            sourceField: 'bom_me_candidate_snapshot_json',
            field: 'attributes.resistance',
          },
          {
            key: 'tolerance',
            label: '误差',
            sourceField: 'bom_me_candidate_snapshot_json',
            field: 'attributes.tolerance_pct',
            format: 'percent',
          },
          {
            key: 'brand',
            label: '品牌',
            sourceField: 'bom_me_candidate_snapshot_json',
            field: 'brand',
            hideWhenEmpty: true,
          },
          {
            key: 'mpn',
            label: 'MPN',
            sourceField: 'bom_me_candidate_snapshot_json',
            field: 'mpn',
            hideWhenEmpty: true,
          },
        ],
      },
      selectedFields: [
        {
          key: 'matchSource',
          label: '匹配来源',
          sourceField: 'bom_me_evidence_json',
          field: 'matchSource',
        },
      ],
      actions: [
        {
          code: 'confirm_candidate',
          label: '确认候选',
          disabledWhen:
            "record.bom_std_material_code !== undefined && record.bom_std_material_code !== null && record.bom_std_material_code !== ''",
          onClick: { action: 'dataSource.reload', args: { ids: ['taskSummary', 'standardLines'] } },
        },
        {
          code: 'undo_decision',
          label: '撤销决策',
          variant: 'secondary',
          requiresSelection: false,
          disabledWhen:
            "record.bom_std_reason_code !== 'manual_confirm' && record.bom_std_reason_code !== 'manual_override'",
          onClick: { action: 'dataSource.reload', args: { ids: ['taskSummary', 'standardLines'] } },
        },
      ],
    },
    exportImpact: {
      dataSource: 'exports',
      fields: [{ key: 'dirty', label: '导出状态', value: '确认/撤销后需重新生成' }],
      actions: [
        {
          code: 'download_new_bom',
          label: '重新生成并下载',
          variant: 'primary',
          onClick: { action: 'dataSource.reload', args: { ids: ['exports'] } },
        },
      ],
    },
  };

  it('renders the selected row in a unified floating review drawer', () => {
    const runtime = makeReviewDrawerRuntime();

    render(<ReviewDrawerBlockRenderer block={reviewDrawerBlock} runtime={runtime} />);

    const drawer = screen.getByTestId('review-drawer');
    expect(drawer).toHaveTextContent('Row 5 · LED1 · 待确认');
    expect(drawer).toHaveClass('fixed');
    expect(drawer).toHaveStyle({ left: '24px', top: '24px', width: '1100px' });
    expect(screen.getByTestId('review-drawer-badge-profile')).toHaveTextContent('Profile');
    expect(screen.getByTestId('review-drawer-badge-profile')).toHaveTextContent('96%');
    expect(screen.queryByRole('tab', { name: '原始 vs 转换' })).not.toBeInTheDocument();
    expect(screen.getByTestId('review-drawer-tab-compare')).toBeInTheDocument();
    expect(screen.getByTestId('review-drawer-tab-source')).toBeInTheDocument();
    expect(screen.getByTestId('review-drawer-tab-candidates')).toBeInTheDocument();
    expect(screen.getByTestId('review-drawer-parse-summary')).toHaveTextContent(
      'JIEJIA_WB_FLEX_MAIN_V1',
    );
    expect(screen.getByTestId('review-drawer-parse-summary')).toHaveTextContent(
      'Description + Value + Footprint',
    );
    expect(screen.getByText('Designator')).toBeInTheDocument();
    expect(screen.getByText('LED1')).toBeInTheDocument();
    expect(screen.getByText('待确认候选后写入')).toBeInTheDocument();
    expect(screen.getByTestId('review-drawer-tab-source')).not.toHaveAttribute('open');
    expect(screen.getByText('Profile Detector')).not.toBeVisible();
    expect(screen.getByText('generate_material_code')).not.toBeVisible();
    expect(screen.getByTestId('review-drawer-source-json')).not.toBeVisible();
    expect(screen.getByTestId('review-drawer-export-action-download_new_bom')).toHaveTextContent(
      '重新生成并下载',
    );
    expect(screen.queryByText('Rev 04')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '收起复核浮层' }));
    expect(screen.getByTestId('review-drawer-minimized')).toHaveTextContent('展开行级复核');
    fireEvent.click(screen.getByRole('button', { name: '展开复核浮层' }));
    expect(screen.getByTestId('review-drawer')).toHaveTextContent('Row 5 · LED1 · 待确认');
  });

  it('renders configured decision fields instead of BOM defaults', () => {
    const runtime = makeReviewDrawerRuntime({
      ...selectedLine,
      referenced_rule: '规则A · Excel行 12',
      rule_formula: '数量×点数×单价；最低收费已纳入',
      review_note: '完全匹配，自动核算',
    });

    render(
      <ReviewDrawerBlockRenderer
        block={{
          ...reviewDrawerBlock,
          compare: {
            rawTitle: '加工费输入',
            canonicalTitle: '加工费规则',
            rawFields: [{ key: 'line', label: '报价行', field: 'bom_std_refdes' }],
            canonicalFields: [{ key: 'rule', label: '引用规则', field: 'referenced_rule' }],
          },
          candidates: {
            title: '加工费核算',
            decisionTitle: '核算结论',
            decisionFields: [
              { key: 'rule', label: '引用规则', field: 'referenced_rule' },
              { key: 'formula', label: '公式', field: 'rule_formula' },
              { key: 'note', label: '提醒', field: 'review_note' },
            ],
            empty: { title: '规则以 Excel 为准' },
          },
        }}
        runtime={runtime}
      />,
    );

    expect(screen.getByText('核算结论')).toBeInTheDocument();
    expect(screen.getAllByText('引用规则').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('规则A · Excel行 12').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('数量×点数×单价；最低收费已纳入')).toBeInTheDocument();
    expect(screen.queryByText('标准编码')).not.toBeInTheDocument();
  });

  it('omits empty compare/source/export panels when only candidate review is configured', () => {
    const runtime = makeRuntime({
      data: {
        priceEvidence: [
          {
            pid: 'EV-1',
            qo_pe_part_no: 'D210000007900',
            qo_pe_source: 'deepseek_llm',
            qo_pe_unit_price: '8.50',
            qo_pe_currency: 'CNY',
            qo_pe_confidence: '0.35',
            qo_pe_status: 'suggested',
          },
        ],
      },
      getContext: () => ({
        locale: 'zh-CN',
        t: (k: string) => k,
        global: {},
        state: {
          selectedPriceLine: {
            pid: 'QL-1',
            qo_ql_mpn: 'D210000007900',
            qo_ql_risk: 'shortage',
          },
        },
      }),
    }) as any;

    render(
      <ReviewDrawerBlockRenderer
        block={{
          id: 'price_review_drawer',
          blockType: 'review-drawer',
          context: '${state.selectedPriceLine}',
          titleTemplate: '${record.qo_ql_mpn}',
          candidates: {
            dataSource: 'priceEvidence',
            title: '查价候选(多源对比)',
            item: {
              titleField: 'qo_pe_part_no',
              scoreField: 'qo_pe_confidence',
              detailFields: [
                {
                  key: 'source',
                  label: '来源',
                  field: 'qo_pe_source',
                  valueMap: {
                    deepseek_llm: 'DeepSeek建议',
                  },
                },
                { key: 'price', label: '单价', field: 'qo_pe_unit_price' },
                {
                  key: 'status',
                  label: '状态',
                  field: 'qo_pe_status',
                  valueMap: {
                    suggested: '建议价',
                  },
                },
              ],
            },
            decisionFields: [{ key: 'risk', label: '风险', field: 'qo_ql_risk' }],
          },
        }}
        runtime={runtime}
      />,
    );

    expect(screen.getByTestId('review-drawer-tab-candidates')).toHaveTextContent(
      '查价候选(多源对比)',
    );
    expect(screen.getByTestId('review-drawer-candidate-EV-1')).toHaveTextContent('DeepSeek建议');
    expect(screen.getByTestId('review-drawer-candidate-EV-1')).toHaveTextContent('建议价');
    expect(screen.getByTestId('review-drawer')).not.toHaveTextContent('Raw');
    expect(screen.getByTestId('review-drawer')).not.toHaveTextContent('Canonical');
    expect(screen.queryByTestId('review-drawer-tab-compare')).not.toBeInTheDocument();
    expect(screen.queryByTestId('review-drawer-tab-source')).not.toBeInTheDocument();
    expect(screen.queryByTestId('review-drawer-tab-export')).not.toBeInTheDocument();
  });

  it('keeps long BOM refdes titles constrained so drawer actions remain visible', () => {
    const longRefdes = Array.from({ length: 48 }, (_, index) => `C${1000 + index}`).join(',');
    const runtime = makeReviewDrawerRuntime({
      ...selectedLine,
      bom_std_refdes: longRefdes,
    });

    render(<ReviewDrawerBlockRenderer block={reviewDrawerBlock} runtime={runtime} />);

    const title = screen.getByRole('heading', {
      name: new RegExp(`Row 5 · ${longRefdes}`),
    });
    expect(title).toHaveClass('flex-1', 'min-w-0', 'truncate');
    expect(title).toHaveAttribute('title', `Row 5 · ${longRefdes} · 待确认`);
    expect(screen.getByRole('button', { name: '下一行' })).toBeVisible();
    expect(screen.getByTestId('review-drawer-tab-candidates')).toHaveClass('min-w-0');
  });

  it('keeps the workbench unobstructed until a row is selected', () => {
    const runtime = makeRuntime({
      getContext: () => ({
        locale: 'zh-CN',
        t: (k: string) => k,
        form: { pid: 'task-1' },
        global: {},
        state: {},
      }),
    }) as any;

    render(<ReviewDrawerBlockRenderer block={reviewDrawerBlock} runtime={runtime} />);

    expect(screen.getByTestId('review-drawer-empty')).toHaveTextContent('Select a row');
    expect(screen.queryByTestId('review-drawer')).not.toBeInTheDocument();
  });

  it('selects a candidate and runs candidate/export actions from the drawer', async () => {
    const runtime = makeReviewDrawerRuntime();

    render(<ReviewDrawerBlockRenderer block={reviewDrawerBlock} runtime={runtime} />);

    const candidateCard = screen.getByTestId('review-drawer-candidate-ME-1');
    expect(screen.getByTestId('review-drawer-candidate-list')).toHaveClass('flex-1');
    expect(screen.getByTestId('review-drawer-tab-candidates')).toHaveClass('h-full');
    expect(screen.getByTestId('review-drawer-decision-panel')).toHaveClass(
      'max-h-[48%]',
      'overflow-auto',
    );
    expect(candidateCard).toHaveClass('block', 'p-3');
    expect(candidateCard).toHaveTextContent('D410000006100');
    expect(candidateCard).toHaveTextContent('贴片电阻 240Ω ±1% 0201');
    expect(candidateCard).toHaveTextContent('0201');
    expect(candidateCard).toHaveTextContent('240Ω');
    expect(candidateCard).toHaveTextContent('1%');
    expect(candidateCard).not.toHaveTextContent('品牌');
    expect(candidateCard).not.toHaveTextContent('MPN');
    expect(screen.getByTestId('review-drawer-candidate-action-confirm_candidate')).toBeDisabled();
    expect(screen.getByTestId('review-drawer-candidate-action-undo_decision')).toBeDisabled();

    fireEvent.click(screen.getByTestId('review-drawer-candidate-ME-1'));
    expect(screen.getByText('匹配来源')).toBeInTheDocument();
    expect(screen.getByText('mpn_exact')).toBeInTheDocument();
    expect(runtime.__updateState).toHaveBeenCalledWith(
      'scope-1',
      'selectedCandidate',
      expect.objectContaining({ pid: 'ME-1', bom_me_material_code: 'D410000006100' }),
    );
    expect(
      screen.getByTestId('review-drawer-candidate-action-confirm_candidate'),
    ).not.toBeDisabled();
    expect(screen.getByTestId('review-drawer-candidate-action-undo_decision')).toBeDisabled();

    fireEvent.click(screen.getByTestId('review-drawer-candidate-action-confirm_candidate'));
    expect(runtime.__reload).toHaveBeenCalledWith(['taskSummary', 'standardLines']);
    await waitFor(() => {
      expect(
        screen.getByTestId('review-drawer-candidate-action-confirm_candidate'),
      ).not.toBeDisabled();
    });

    expect(screen.getByText('导出状态')).not.toBeVisible();
    fireEvent.click(screen.getByTestId('review-drawer-export-action-download_new_bom'));
    expect(runtime.__reload).toHaveBeenCalledWith(['exports']);
  });

  it('wraps long candidate detail values instead of truncating price and BOM evidence', () => {
    const runtime = makeReviewDrawerRuntime();

    render(<ReviewDrawerBlockRenderer block={reviewDrawerBlock} runtime={runtime} />);

    const specField = screen.getByTestId('review-drawer-candidate-ME-1-field-spec');
    expect(specField).toHaveClass('grid-cols-[72px_minmax(0,1fr)]');
    const specValue = specField.querySelector('dd');
    expect(specValue).not.toBeNull();
    expect(specValue).toHaveClass('break-words', 'whitespace-normal');
    expect(specValue).not.toHaveClass('truncate');
  });

  it('opens a configured candidate action form and merges values into command payload', async () => {
    const fetchResultMock = vi.mocked(fetchResult);
    fetchResultMock.mockResolvedValueOnce({ code: '0', data: {} } as any);
    const runtime = makeRuntime({
      getContext: () => ({
        locale: 'zh-CN',
        t: (k: string) => k,
        global: {},
        state: {
          selectedPriceLine: {
            pid: 'QL-1',
            qo_ql_mpn: 'D210000007900',
          },
        },
      }),
    }) as any;

    render(
      <ReviewDrawerBlockRenderer
        block={{
          id: 'price_review_drawer',
          blockType: 'review-drawer',
          context: '${state.selectedPriceLine}',
          titleTemplate: '${record.qo_ql_mpn}',
          candidates: {
            dataSource: 'priceEvidence',
            title: '查价候选',
            actions: [
              {
                code: 'record_manual_price',
                label: '录入人工价',
                requiresSelection: false,
                onClick: {
                  action: 'command.execute',
                  args: {
                    command: 'qo_quote_line_common:record_manual_price',
                    targetRecordId: '${state.selectedPriceLine.pid}',
                    operationType: 'update',
                    payload: {
                      source: 'manual',
                    },
                    reload: ['priceEvidence'],
                  },
                },
                form: {
                  title: '录入人工价',
                  submitLabel: '录入并采用',
                  fields: [
                    { name: 'unitPrice', label: '人工单价', type: 'number', required: true },
                    { name: 'currency', label: '币种', type: 'text', defaultValue: 'CNY' },
                    { name: 'reason', label: '来源说明', type: 'textarea' },
                  ],
                },
              },
            ],
          },
        }}
        runtime={runtime}
      />,
    );

    fireEvent.click(screen.getByTestId('review-drawer-candidate-action-record_manual_price'));

    expect(screen.getByTestId('review-drawer-action-form')).toHaveTextContent('录入人工价');
    fireEvent.change(screen.getByTestId('review-drawer-action-form-field-unitPrice'), {
      target: { value: '8.88' },
    });
    fireEvent.change(screen.getByTestId('review-drawer-action-form-field-reason'), {
      target: { value: '业务裁决价' },
    });
    fireEvent.click(screen.getByTestId('review-drawer-action-form-submit'));

    await waitFor(() => {
      expect(fetchResultMock).toHaveBeenCalledWith(
        '/api/meta/commands/execute/qo_quote_line_common:record_manual_price',
        expect.objectContaining({
          method: 'post',
          params: expect.objectContaining({
            targetRecordId: 'QL-1',
            operationType: 'UPDATE',
            payload: expect.objectContaining({
              source: 'manual',
              unitPrice: '8.88',
              currency: 'CNY',
              reason: '业务裁决价',
            }),
          }),
        }),
      );
    });
    expect(runtime.__reload).toHaveBeenCalledWith(['priceEvidence']);
  });

  it('honors drawer action conditions for confirmed review decisions', () => {
    const runtime = makeReviewDrawerRuntime({
      ...selectedLine,
      bom_std_material_code: 'D790000012300',
      bom_std_reason_code: 'manual_confirm',
    });

    render(<ReviewDrawerBlockRenderer block={reviewDrawerBlock} runtime={runtime} />);

    fireEvent.click(screen.getByTestId('review-drawer-candidate-ME-1'));

    expect(screen.getByTestId('review-drawer-candidate-action-confirm_candidate')).toBeDisabled();
    expect(screen.getByTestId('review-drawer-candidate-action-undo_decision')).not.toBeDisabled();
  });

  it('refreshes the selected row from its backing data source after reloads', () => {
    const runtime = makeRuntime({
      data: {
        standardLines: [
          {
            ...selectedLine,
            bom_std_material_code: 'D790000099999',
            bom_std_reason_code: 'manual_confirm',
          },
        ],
      },
      getContext: () => ({
        locale: 'zh-CN',
        t: (k: string) => k,
        form: { pid: 'task-1' },
        global: {},
        state: { selectedBomLine: selectedLine },
      }),
    }) as any;

    render(
      <ReviewDrawerBlockRenderer
        block={{
          ...reviewDrawerBlock,
          contextDataSource: 'standardLines',
          contextKeyField: 'pid',
          titleTemplate: 'Row ${record.bom_std_row_no} · ${record.bom_std_reason_code}',
        }}
        runtime={runtime}
      />,
    );

    expect(screen.getAllByText('D790000099999').length).toBeGreaterThan(0);
    expect(screen.getByTestId('review-drawer')).toHaveTextContent('manual_confirm');
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

  it('refreshes once after subscribing so sibling mount-time state writes are not missed', async () => {
    const context: Record<string, any> = {
      locale: 'en-US',
      t: (k: string) => k,
      form: {},
      global: {},
      state: {},
    };
    const runtime = makeRuntime({
      getContext: () => context,
      getStateManager: () => ({
        getStore: () => ({
          subscribe: () => () => undefined,
        }),
      }),
      getScopeId: () => 'scope-1',
    });

    const Writer = () => {
      React.useEffect(() => {
        context.state.selectedLine = { materialCode: 'D410000098100' };
      }, []);
      return null;
    };
    const Reader = () => {
      useRuntimeStateSubscription(runtime);
      return <div>{runtime.getContext().state.selectedLine?.materialCode ?? 'empty'}</div>;
    };

    render(
      <>
        <Writer />
        <Reader />
      </>,
    );

    expect(await screen.findByText('D410000098100')).toBeInTheDocument();
  });
});

describe('EvidencePanelBlockRenderer', () => {
  it('renders an empty state when the configured context is missing', () => {
    const runtime = makeRuntime();
    const block: BlockConfig = {
      id: 'evidence',
      blockType: 'evidence-panel',
      context: '${state.selectedCandidate}',
      empty: { title: 'Select evidence' },
    };

    render(<EvidencePanelBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByTestId('evidence-panel-empty')).toHaveTextContent('Select evidence');
  });

  it('renders configured evidence sections and formats JSON payloads', () => {
    const runtime = makeRuntime({
      getContext: () => ({
        locale: 'en-US',
        t: (k: string) => k,
        form: {},
        global: {},
        state: {
          selectedCandidate: {
            materialCode: 'D410000098100',
            evidenceJson: '{"spec":"62R","package":"0603"}',
            conflictJson: { brand: 'alternative' },
          },
        },
      }),
    });
    const block: BlockConfig = {
      id: 'evidence',
      blockType: 'evidence-panel',
      context: '${state.selectedCandidate}',
      title: 'Evidence',
      sections: [
        { key: 'candidate', label: 'Candidate', field: 'materialCode' },
        { key: 'evidence', label: 'Evidence JSON', field: 'evidenceJson', format: 'json' },
        { key: 'conflict', label: 'Conflict JSON', field: 'conflictJson', format: 'json' },
      ],
    };

    render(<EvidencePanelBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByTestId('evidence-panel')).toHaveTextContent('Evidence');
    expect(screen.getByTestId('evidence-panel-section-candidate')).toHaveTextContent(
      'D410000098100',
    );
    expect(screen.getByTestId('evidence-panel-section-evidence')).toHaveTextContent(
      '"spec": "62R"',
    );
    expect(screen.getByTestId('evidence-panel-section-conflict')).toHaveTextContent(
      '"brand": "alternative"',
    );
  });
});

describe('ArtifactTimelineBlockRenderer', () => {
  it('renders a stable empty state when no artifacts exist', () => {
    const runtime = makeRuntime({
      data: {
        exports: [],
      },
    });
    const block: BlockConfig = {
      id: 'exports',
      blockType: 'artifact-timeline',
      dataSource: 'exports',
      empty: { title: 'No artifacts' },
    };

    render(<ArtifactTimelineBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByTestId('artifact-timeline-empty')).toHaveTextContent('No artifacts');
  });

  it('renders artifact rows with status, hash and download link', () => {
    const runtime = makeRuntime({
      data: {
        exports: [
          {
            pid: 'export-1',
            revisionNo: 2,
            filename: 'standard-bom.xlsx',
            generatedAt: '2026-06-06T15:32:00+08:00',
            status: 'generated',
            stateHash: 'abcdef1234567890',
            fileId: 'file-1',
          },
        ],
      },
    });
    const block: BlockConfig = {
      id: 'exports',
      blockType: 'artifact-timeline',
      dataSource: 'exports',
      item: {
        titleField: 'filename',
        subtitleField: 'generatedAt',
        revisionField: 'revisionNo',
        statusField: 'status',
        hashField: 'stateHash',
        fileIdField: 'fileId',
      },
    };

    render(<ArtifactTimelineBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByTestId('artifact-timeline')).toHaveTextContent('standard-bom.xlsx');
    expect(screen.getByTestId('artifact-timeline-item-export-1')).toHaveTextContent('Rev 2');
    expect(screen.getByTestId('artifact-timeline-item-export-1')).toHaveTextContent('generated');
    expect(screen.getByTestId('artifact-timeline-item-export-1')).toHaveTextContent('abcdef12');
    expect(screen.getByTestId('artifact-timeline-download-export-1')).toHaveAttribute(
      'href',
      '/api/file/download/file-1',
    );
  });
});
