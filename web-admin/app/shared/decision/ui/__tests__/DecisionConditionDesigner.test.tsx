import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { DecisionConditionDesigner } from '../DecisionConditionDesigner';
import { type FieldOption } from '../ConditionBuilder';
import { group, cmp, path, lit } from '../../ast/conditionAst';
import type { DecisionApi, ValidateResult } from '../../api/decisionApi';

const FIELDS: FieldOption[] = [
  { scope: 'record', path: 'data.priority', label: '优先级', dataType: 'enum', options: ['HIGH', 'LOW'] },
  { scope: 'record', path: 'data.amount', label: '金额', dataType: 'decimal' },
];

const initial = () => group('AND', [cmp(path('record', 'data.priority', 'enum'), 'EQ', lit('HIGH', 'enum'))]);

function apiWith(validateImpl: () => Promise<ValidateResult>): DecisionApi {
  return { validate: vi.fn(validateImpl) } as unknown as DecisionApi;
}

describe('DecisionConditionDesigner', () => {
  it('renders builder + serialized AST + samples', () => {
    const samples = [{ label: '高优', context: { record: { data: { priority: 'HIGH' } } } }];
    render(<DecisionConditionDesigner api={apiWith(async () => ({ valid: true }))} fields={FIELDS}
      initial={initial()} samples={samples} />);
    expect(screen.getByTestId('condition-builder')).toBeInTheDocument();
    expect(screen.getByTestId('condition-testrun')).toBeInTheDocument();
    expect(screen.getByTestId('dcd-ast')).toHaveTextContent('"operator":"EQ"');
  });

  it('calls backend validate with the current AST and shows success + fieldRefs', async () => {
    const api = apiWith(async () => ({ valid: true, fieldRefs: ['record.data.priority'] }));
    render(<DecisionConditionDesigner api={api} fields={FIELDS} initial={initial()} />);
    fireEvent.click(screen.getByTestId('dcd-validate'));
    await waitFor(() => expect(screen.getByTestId('dcd-valid')).toBeInTheDocument());
    expect(api.validate).toHaveBeenCalledWith('SIMPLE_CONDITION', 'AST_EVALUATOR', expect.objectContaining({ type: 'group' }));
    expect(screen.getByTestId('dcd-fieldrefs')).toHaveTextContent('record.data.priority');
  });

  it('shows validation errors when invalid', async () => {
    const api = apiWith(async () => ({ valid: false, errors: [{ code: 'AST_STRUCTURE', message: 'bad' }] }));
    render(<DecisionConditionDesigner api={api} fields={FIELDS} initial={initial()} />);
    fireEvent.click(screen.getByTestId('dcd-validate'));
    await waitFor(() => expect(screen.getByTestId('dcd-errors')).toBeInTheDocument());
    expect(screen.getByTestId('dcd-errors')).toHaveTextContent('AST_STRUCTURE');
    expect(screen.getByTestId('dcd-validation')).toHaveAttribute('data-valid', 'false');
  });

  it('editing the builder updates the serialized AST (composition works)', () => {
    render(<DecisionConditionDesigner api={apiWith(async () => ({ valid: true }))} fields={FIELDS} initial={initial()} />);
    fireEvent.click(screen.getByTestId('cb-add'));
    // a second row added -> the AST pre reflects two compare children
    const ast = screen.getByTestId('dcd-ast').textContent || '';
    expect((ast.match(/"type":"compare"/g) || []).length).toBe(2);
  });
});
