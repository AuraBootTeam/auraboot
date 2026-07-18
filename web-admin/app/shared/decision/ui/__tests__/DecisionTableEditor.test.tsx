import { useState } from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { DecisionTableEditor } from '../DecisionTableEditor';
import { type DecisionTable } from '../../table/decisionTable';

const base = (): DecisionTable => ({
  hitPolicy: 'FIRST',
  inputs: [
    { id: 'amount', label: '金额', scope: 'record', path: 'data.amount', dataType: 'decimal' },
    { id: 'priority', label: '优先级', scope: 'record', path: 'data.priority', dataType: 'enum' },
  ],
  outputs: [{ id: 'route', label: '路由', dataType: 'string' }],
  rules: [],
});

function Harness() {
  const [v, setV] = useState<DecisionTable>(base());
  return (
    <>
      <DecisionTableEditor value={v} onChange={setV} />
      <div data-testid="dump">{JSON.stringify(v)}</div>
    </>
  );
}

describe('DecisionTableEditor', () => {
  it('renders input/output headers and empty placeholder', () => {
    render(<Harness />);
    expect(screen.getByTestId('dt-in-amount')).toHaveTextContent('金额');
    expect(screen.getByTestId('dt-out-route')).toHaveTextContent('路由');
    expect(screen.getByTestId('dt-empty')).toBeInTheDocument();
  });

  it('adds and deletes rule rows', () => {
    render(<Harness />);
    fireEvent.click(screen.getByTestId('dt-add-rule'));
    expect(screen.getByTestId('dt-row-0')).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText('delete-row-0'));
    expect(screen.queryByTestId('dt-row-0')).not.toBeInTheDocument();
  });

  it('edits a cell operator + value and an output, reflected in emitted table', () => {
    render(<Harness />);
    fireEvent.click(screen.getByTestId('dt-add-rule'));
    fireEvent.change(screen.getByLabelText('op-0-amount'), { target: { value: 'GT' } });
    fireEvent.change(screen.getByLabelText('val-0-amount'), { target: { value: '10000' } });
    fireEvent.change(screen.getByLabelText('out-0-route'), { target: { value: 'director' } });
    const dump = JSON.parse(screen.getByTestId('dump').textContent || '{}') as DecisionTable;
    expect(dump.rules[0].when.amount).toMatchObject({ operator: 'GT', value: '10000' });
    expect(dump.rules[0].then.route).toBe('director');
  });

  it('changes the hit policy', () => {
    render(<Harness />);
    fireEvent.change(screen.getByLabelText('hit-policy'), { target: { value: 'UNIQUE' } });
    const dump = JSON.parse(screen.getByTestId('dump').textContent || '{}') as DecisionTable;
    expect(dump.hitPolicy).toBe('UNIQUE');
  });

  it('shows aggregation controls for COLLECT', () => {
    render(<Harness />);
    fireEvent.change(screen.getByLabelText('hit-policy'), { target: { value: 'COLLECT' } });
    fireEvent.change(screen.getByLabelText('collect-aggregation'), { target: { value: 'SUM' } });
    const dump = JSON.parse(screen.getByTestId('dump').textContent || '{}') as DecisionTable;
    expect(dump.hitPolicy).toBe('COLLECT');
    expect(dump.aggregation).toBe('SUM');
  });

  it('adds, reorders, edits and deletes input columns', () => {
    render(<Harness />);
    fireEvent.click(screen.getByTestId('dt-add-input'));
    fireEvent.change(screen.getByLabelText('input-label-2'), { target: { value: '风险等级' } });
    fireEvent.change(screen.getByLabelText('input-path-2'), { target: { value: 'data.risk' } });
    fireEvent.click(screen.getByLabelText('move-input-up-2'));
    fireEvent.click(screen.getByLabelText('delete-input-2'));
    const dump = JSON.parse(screen.getByTestId('dump').textContent || '{}') as DecisionTable;
    expect(dump.inputs.map((i) => i.label)).toEqual(['金额', '风险等级']);
    expect(dump.inputs[1].path).toBe('data.risk');
  });

  it('selects a fact field for a DMN input and migrates existing rule cells', () => {
    function FieldPickerHarness() {
      const [v, setV] = useState<DecisionTable>({
        ...base(),
        rules: [
          {
            ruleId: 'row-1',
            priority: 10,
            when: { amount: { operator: 'EQ', value: '', feel: '> 30' } },
            then: { route: 'escalate' },
          },
        ],
      });
      return (
        <>
          <DecisionTableEditor
            value={v}
            onChange={setV}
            fieldOptions={[
              {
                scope: 'sla',
                path: 'deadlineMinutes',
                label: '截止分钟',
                dataType: 'integer',
                options: ['30', '60', '120'],
              },
              {
                scope: 'record',
                path: 'data.targetKey',
                label: 'SLA 节点',
                dataType: 'string',
                modelName: 'SLA 配置',
              },
            ]}
          />
          <div data-testid="dump">{JSON.stringify(v)}</div>
        </>
      );
    }

    render(<FieldPickerHarness />);

    fireEvent.click(screen.getByTestId('dt-input-field-picker-0'));
    expect(screen.getByTestId('dt-input-field-picker-panel-0')).toHaveTextContent('SLA 上下文');
    expect(screen.getByTestId('dt-input-field-picker-panel-0')).toHaveTextContent('整数');
    expect(screen.getByTestId('dt-input-field-picker-panel-0')).not.toHaveTextContent('sla.deadlineMinutes');
    expect(screen.getByTestId('dt-input-field-picker-panel-0')).not.toHaveTextContent('record.data.targetKey');
    fireEvent.change(screen.getByLabelText('input-field-search-0'), {
      target: { value: '截止' },
    });
    fireEvent.click(screen.getByTestId('dt-input-field-option-0-sla-deadlineMinutes'));

    const dump = JSON.parse(screen.getByTestId('dump').textContent || '{}') as DecisionTable;
    expect(dump.inputs[0]).toMatchObject({
      id: 'sla_deadlineMinutes',
      label: '截止分钟',
      scope: 'sla',
      path: 'deadlineMinutes',
      dataType: 'integer',
      allowedValues: ['30', '60', '120'],
    });
    expect(dump.rules[0].when.sla_deadlineMinutes).toMatchObject({
      operator: 'EQ',
      value: '',
      feel: '> 30',
    });
    expect(dump.rules[0].when.amount).toBeUndefined();
  });

  it('renders fact catalog value labels in DMN cells while preserving raw dict values', () => {
    function DictLabelHarness() {
      const [v, setV] = useState<DecisionTable>(base());
      return (
        <>
          <DecisionTableEditor
            value={v}
            onChange={setV}
            fieldOptions={[
              {
                scope: 'record',
                path: 'data.wd_req_type',
                label: '请假类型',
                dataType: 'dict',
                options: ['annual', 'sick'],
                valueLabels: {
                  annual: '年假',
                  sick: '病假',
                },
              },
            ]}
          />
          <div data-testid="dump">{JSON.stringify(v)}</div>
        </>
      );
    }

    render(<DictLabelHarness />);

    fireEvent.click(screen.getByTestId('dt-input-field-picker-0'));
    fireEvent.click(screen.getByTestId('dt-input-field-option-0-record-data_wd_req_type'));
    fireEvent.click(screen.getByTestId('dt-add-rule'));

    const selector = screen.getByLabelText('val-0-record_data_wd_req_type') as HTMLSelectElement;
    expect(selector).toHaveTextContent('年假');
    expect(selector).toHaveTextContent('病假');
    expect(selector).not.toHaveTextContent('annual');

    fireEvent.change(selector, { target: { value: 'annual' } });

    const dump = JSON.parse(screen.getByTestId('dump').textContent || '{}') as DecisionTable;
    expect(dump.inputs[0]).toMatchObject({
      id: 'record_data_wd_req_type',
      label: '请假类型',
      allowedValues: ['annual', 'sick'],
      valueLabels: { annual: '年假', sick: '病假' },
    });
    expect(dump.rules[0].when.record_data_wd_req_type.value).toBe('annual');
  });

  it('edits dict IN cells as multi-select arrays with value labels', () => {
    function DictInHarness() {
      const [v, setV] = useState<DecisionTable>(base());
      return (
        <>
          <DecisionTableEditor
            value={v}
            onChange={setV}
            fieldOptions={[
              {
                scope: 'record',
                path: 'data.wd_req_type',
                label: '请假类型',
                dataType: 'dict',
                options: ['annual', 'sick'],
                valueLabels: {
                  annual: '年假',
                  sick: '病假',
                },
              },
            ]}
          />
          <div data-testid="dump">{JSON.stringify(v)}</div>
        </>
      );
    }

    render(<DictInHarness />);

    fireEvent.click(screen.getByTestId('dt-input-field-picker-0'));
    fireEvent.click(screen.getByTestId('dt-input-field-option-0-record-data_wd_req_type'));
    fireEvent.click(screen.getByTestId('dt-add-rule'));

    const operator = screen.getByLabelText('op-0-record_data_wd_req_type') as HTMLSelectElement;
    expect(operator).toHaveTextContent('属于集合');
    expect(operator).toHaveTextContent('不在集合');
    expect(operator).not.toHaveTextContent('IN');

    fireEvent.change(operator, { target: { value: 'IN' } });
    const selector = screen.getByLabelText('val-0-record_data_wd_req_type') as HTMLSelectElement;
    expect(selector.multiple).toBe(true);
    expect(selector).toHaveTextContent('年假');
    expect(selector).toHaveTextContent('病假');
    expect(selector).not.toHaveTextContent('annual');

    Array.from(selector.options).forEach((option) => {
      option.selected = ['annual', 'sick'].includes(option.value);
    });
    fireEvent.change(selector);

    const dump = JSON.parse(screen.getByTestId('dump').textContent || '{}') as DecisionTable;
    expect(dump.rules[0].when.record_data_wd_req_type).toMatchObject({
      operator: 'IN',
      value: ['annual', 'sick'],
    });
  });

  it('exposes temporal FEEL data types for input columns', () => {
    render(<Harness />);
    fireEvent.click(screen.getByTestId('dt-add-input'));
    fireEvent.change(screen.getByLabelText('input-data-type-2'), { target: { value: 'duration' } });
    const dump = JSON.parse(screen.getByTestId('dump').textContent || '{}') as DecisionTable;
    expect(dump.inputs[2].dataType).toBe('duration');
  });

  it('edits output allowed values for PRIORITY', () => {
    render(<Harness />);
    fireEvent.change(screen.getByLabelText('hit-policy'), { target: { value: 'PRIORITY' } });
    fireEvent.change(screen.getByLabelText('output-allowed-values-0'), { target: { value: 'HIGH,MEDIUM,LOW' } });
    const dump = JSON.parse(screen.getByTestId('dump').textContent || '{}') as DecisionTable;
    expect(dump.outputs[0].allowedValues).toEqual(['HIGH', 'MEDIUM', 'LOW']);
  });

  it('edits FEEL text per input cell', () => {
    render(<Harness />);
    fireEvent.click(screen.getByTestId('dt-add-rule'));
    fireEvent.change(screen.getByLabelText('feel-0-amount'), { target: { value: '[10000..50000]' } });
    const dump = JSON.parse(screen.getByTestId('dump').textContent || '{}') as DecisionTable;
    expect(dump.rules[0].when.amount.feel).toBe('[10000..50000]');
  });

  it('triggers analysis and renders gap/conflict issue details', () => {
    const onAnalyze = vi.fn();
    render(
      <DecisionTableEditor
        value={base()}
        onChange={vi.fn()}
        onAnalyze={onAnalyze}
        analysis={{
          valid: false,
          metrics: {
            ruleCount: 2,
            gapCount: 1,
            overlapCount: 1,
            conflictCount: 1,
            unreachableRuleCount: 1,
            finiteCombinationCount: 4,
            finiteDomainComplete: false,
            continuousInputCount: 1,
            analysisDurationMs: 12,
          },
          errors: [{
            code: 'DMN_CONFLICT',
            severity: 'ERROR',
            ruleIds: ['r1', 'r2'],
            inputCombination: { amount: 100 },
            message: 'Rules produce different outputs',
          }],
          warnings: [{
            code: 'DMN_CONTINUOUS_GAP',
            severity: 'WARNING',
            inputCombination: { input: 'amount' },
            metadata: { gapRanges: ['[10..20]'], coveredRanges: ['(-inf..10)', '(20..+inf)'] },
            message: 'Input amount has uncovered ranges',
          }],
        }}
      />,
    );

    fireEvent.click(screen.getByTestId('dt-analyze'));

    expect(onAnalyze).toHaveBeenCalledOnce();
    expect(screen.getByTestId('dt-analysis-panel')).toHaveTextContent('存在阻断问题');
    expect(screen.getByTestId('dt-metric-gap')).toHaveTextContent('缺口 1');
    expect(screen.getByTestId('dt-metric-conflict')).toHaveTextContent('冲突 1');
    expect(screen.getByTestId('dt-analysis-issue-0')).toHaveTextContent('输出冲突');
    expect(screen.getByTestId('dt-analysis-issue-0')).not.toHaveTextContent('DMN_CONFLICT');
    expect(screen.getByTestId('dt-analysis-issue-0')).toHaveTextContent('规则 r1,r2');
    expect(screen.getByTestId('dt-analysis-issue-1')).toHaveTextContent('连续区间缺口');
    expect(screen.getByTestId('dt-analysis-metadata-1')).toHaveTextContent('缺口范围: [10..20]');
    expect(screen.getByTestId('dt-analysis-panel')).toHaveTextContent('连续输入 1');
    expect(screen.getByTestId('dt-analysis-panel')).toHaveTextContent('12ms');
  });

  it('edits DMN XML and triggers import/export/round-trip actions', () => {
    const onDmnXmlChange = vi.fn();
    const onExportDmnXml = vi.fn();
    const onImportDmnXml = vi.fn();
    const onRoundTripDmnXml = vi.fn();
    render(
      <DecisionTableEditor
        value={base()}
        onChange={vi.fn()}
        dmnXml="<definitions />"
        dmnStatus="Round-trip 通过"
        onDmnXmlChange={onDmnXmlChange}
        onExportDmnXml={onExportDmnXml}
        onImportDmnXml={onImportDmnXml}
        onRoundTripDmnXml={onRoundTripDmnXml}
      />,
    );

    fireEvent.click(screen.getByTestId('dt-export-dmn'));
    fireEvent.change(screen.getByLabelText('dmn-xml'), { target: { value: '<definitions id="x" />' } });
    fireEvent.click(screen.getByTestId('dt-import-dmn'));
    fireEvent.click(screen.getByTestId('dt-roundtrip-dmn'));

    expect(onExportDmnXml).toHaveBeenCalledOnce();
    expect(onDmnXmlChange).toHaveBeenCalledWith('<definitions id="x" />');
    expect(onImportDmnXml).toHaveBeenCalledOnce();
    expect(onRoundTripDmnXml).toHaveBeenCalledOnce();
    expect(screen.getByTestId('dt-dmn-status')).toHaveTextContent('Round-trip 通过');
  });
});
