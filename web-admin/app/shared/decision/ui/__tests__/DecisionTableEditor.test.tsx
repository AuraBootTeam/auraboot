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
          },
          errors: [{
            code: 'DMN_CONFLICT',
            severity: 'ERROR',
            ruleIds: ['r1', 'r2'],
            inputCombination: { amount: 100 },
            message: 'Rules produce different outputs',
          }],
          warnings: [{
            code: 'DMN_GAP',
            severity: 'WARNING',
            inputCombination: { amount: 0 },
            message: 'No rule covers this input combination',
          }],
        }}
      />,
    );

    fireEvent.click(screen.getByTestId('dt-analyze'));

    expect(onAnalyze).toHaveBeenCalledOnce();
    expect(screen.getByTestId('dt-analysis-panel')).toHaveTextContent('存在阻断问题');
    expect(screen.getByTestId('dt-metric-gap')).toHaveTextContent('Gap 1');
    expect(screen.getByTestId('dt-metric-conflict')).toHaveTextContent('Conflict 1');
    expect(screen.getByTestId('dt-analysis-issue-0')).toHaveTextContent('DMN_CONFLICT');
    expect(screen.getByTestId('dt-analysis-issue-0')).toHaveTextContent('rules r1,r2');
    expect(screen.getByTestId('dt-analysis-issue-1')).toHaveTextContent('DMN_GAP');
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
