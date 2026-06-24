import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { ProcessFeeRuleMatrixBlock } from '../ProcessFeeRuleMatrixBlock';

const { mockFetchResult } = vi.hoisted(() => ({
  mockFetchResult: vi.fn(),
}));

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: mockFetchResult,
}));

function makeRuntime(rows: Array<Record<string, unknown>>, selectedRuleSet: Record<string, unknown> = {
  pid: 'RS1',
  qo_pfrs_version: 'PFR-BASE',
  qo_pfrs_status: 'published',
  qo_pfrs_active: true,
}): SchemaRuntime {
  const reload = vi.fn().mockResolvedValue(undefined);
  const context = {
    locale: 'zh-CN',
    t: (key: string) => key,
    form: { pid: 'Q1' },
    state: { selectedProcessRuleSet: selectedRuleSet },
  };

  return {
    getContext: () => context,
    getDataSourceManager: () => ({
      getData: (id: string) => (id === 'processFeeRuleLines' ? { records: rows } : { records: [selectedRuleSet] }),
      reload,
      subscribe: vi.fn(),
    }),
    getStateManager: () => ({
      getStore: () => ({ subscribe: vi.fn() }),
      updateState: vi.fn(),
    }),
    getScopeId: () => 'scope-1',
    __reload: reload,
  } as unknown as SchemaRuntime & { __reload: typeof reload };
}

const block = {
  id: 'qo_process_fee_rule_matrix',
  blockType: 'custom',
  component: 'ProcessFeeRuleMatrixBlock',
  dataSource: 'processFeeRuleLines',
  selectedRuleSet: '${state.selectedProcessRuleSet}',
  quoteId: '${form.pid}',
  saveCommand: 'qo_quote_common:save_process_fee_rule_matrix',
  reload: ['processFeeRuleSets', 'processFeeRuleLines', 'processFeeRuleDiff', 'processCostItems'],
};

describe('ProcessFeeRuleMatrixBlock', () => {
  beforeEach(() => {
    mockFetchResult.mockReset();
    mockFetchResult.mockResolvedValue({
      code: '0',
      success: true,
      data: {
        ruleSetId: 'RS-DRAFT',
        ruleVersion: 'PFR-DRAFT',
        savedLines: 2,
      },
    });
  });

  it('filters, sorts and validates editable rule cells', () => {
    render(
      <ProcessFeeRuleMatrixBlock
        block={block as never}
        runtime={makeRuntime([
          {
            pid: 'RL1',
            qo_pfrl_process_stage: 'SMT',
            qo_pfrl_component_type: '0603',
            qo_pfrl_min_qty: 1,
            qo_pfrl_max_qty: 10000,
            qo_pfrl_unit_price: '0.012',
            qo_pfrl_min_charge: 0,
            qo_pfrl_note: 'base',
          },
          {
            pid: 'RL2',
            qo_pfrl_process_stage: 'DIP插件',
            qo_pfrl_component_type: 'connector',
            qo_pfrl_unit_price: '0.045',
          },
        ])}
      />,
    );

    fireEvent.change(screen.getByLabelText('工序筛选'), { target: { value: 'DIP插件' } });
    expect(screen.getByLabelText('工序筛选')).toHaveValue('DIP插件');
    expect(screen.getAllByTestId('process-fee-cell-qo_pfrl_process_stage')).toHaveLength(1);
    expect(screen.queryByDisplayValue('SMT')).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('工序筛选'), { target: { value: '全部工序' } });
    fireEvent.click(screen.getByTestId('process-fee-sort-unit-price'));
    const firstUnitPrice = screen.getAllByTestId('process-fee-cell-qo_pfrl_unit_price')[0];
    expect(firstUnitPrice).toHaveValue('0.012');

    fireEvent.change(firstUnitPrice, { target: { value: '-1' } });
    expect(screen.getByText('1 行已修改')).toBeInTheDocument();
    expect(screen.getByText(/unit price must be greater than 0/)).toBeInTheDocument();
    expect(screen.getByTestId('process-fee-save-matrix')).toBeDisabled();
  });

  it('accepts pasted TSV rows and saves the edited matrix through the command pipeline', async () => {
    const runtime = makeRuntime([
      {
        pid: 'RL1',
        qo_pfrl_process_stage: 'SMT',
        qo_pfrl_component_type: '0603',
        qo_pfrl_unit_price: '0.012',
      },
    ]) as SchemaRuntime & { __reload: ReturnType<typeof vi.fn> };
    render(<ProcessFeeRuleMatrixBlock block={block as never} runtime={runtime} />);

    fireEvent.change(screen.getByLabelText('粘贴矩阵'), {
      target: {
        value: '工序\t元件类型\t最小数量\t最大数量\t单价\t最低收费\t备注\nSMT\t0402\t1\t5000\t0.011\t0\tpaste\nDIP插件\tconnector\t1\t\t0.045\t30\tpaste',
      },
    });
    fireEvent.click(screen.getByTestId('process-fee-apply-paste'));

    expect(screen.getByDisplayValue('0402')).toBeInTheDocument();
    expect(screen.getByDisplayValue('connector')).toBeInTheDocument();
    expect(screen.getAllByText('2 行已修改').length).toBeGreaterThanOrEqual(1);

    fireEvent.click(screen.getByTestId('process-fee-save-matrix'));

    await waitFor(() => {
      expect(mockFetchResult).toHaveBeenCalledWith('/api/meta/commands/execute/qo_quote_common:save_process_fee_rule_matrix', {
        method: 'post',
        params: expect.objectContaining({
          targetRecordPid: 'Q1',
          operationType: 'SAVE',
          payload: expect.objectContaining({
            ruleSetId: 'RS1',
            rows: expect.arrayContaining([
              expect.objectContaining({
                qo_pfrl_process_stage: 'SMT',
                qo_pfrl_component_type: '0402',
                qo_pfrl_unit_price: '0.011',
              }),
              expect.objectContaining({
                qo_pfrl_process_stage: 'DIP插件',
                qo_pfrl_component_type: 'connector',
                qo_pfrl_min_charge: '30',
              }),
            ]),
          }),
        }),
      });
    });
    await waitFor(() => {
      expect(runtime.__reload).toHaveBeenCalledWith([
        'processFeeRuleSets',
        'processFeeRuleLines',
        'processFeeRuleDiff',
        'processCostItems',
      ]);
    });
    expect(await screen.findByText(/PFR-DRAFT/)).toBeInTheDocument();
  });
});
