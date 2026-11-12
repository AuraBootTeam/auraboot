import { MemoryRouter } from 'react-router';
import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToolbarBlockRenderer } from '../ToolbarBlockRenderer';
import { ExpressionEvaluator } from '../../../runtime/expression/evaluator';

const mock = vi.hoisted(() => ({ handleAction: vi.fn().mockResolvedValue(undefined) }));
vi.mock('~/framework/meta/hooks/useActionHandler', () => ({ useActionHandler: () => ({ handleAction: mock.handleAction, loading: false }) }));
vi.mock('~/contexts/AuthContext', () => ({ useAuth: () => ({ hasPermission: () => true }) }));
vi.mock('~/contexts/ToastContext', () => ({ useToastContext: () => ({}) }));

describe('record-bound detail body toolbar', () => {
  beforeEach(() => mock.handleAction.mockClear());
  it('keeps preview availability tied to local drafts while fresh parent facts override stale context', () => {
    const stale = { pid: 'parent', first_read_at: null };
    const current = { ...stale, first_read_at: '2026-11-07T00:00:00Z' };
    const rows = [{ quantity: '50.000001' }];
    let form: any = { ...stale, rows };
    const runtime = { getContext: () => ({ record: stale, form }),
      getSchema: () => ({ modelCode: 'example' }), getDataSourceManager: () => null,
      getEvaluator: () => new ExpressionEvaluator() };
    const button = { code: 'confirm', label: 'Confirm preview',
      visibleWhen: '${form.previewRows === form.rows && form.first_read_at !== null}',
      action: { type: 'flow', steps: [] } };
    const block = { id: 'body-actions', blockType: 'toolbar', buttons: [button], record: current };
    const view = render(<MemoryRouter><ToolbarBlockRenderer block={block as any} runtime={runtime as any} /></MemoryRouter>);
    expect(screen.queryByRole('button', { name: 'Confirm preview' })).not.toBeInTheDocument();
    form = { ...form, previewRows: rows };
    view.rerender(<MemoryRouter><ToolbarBlockRenderer block={block as any} runtime={runtime as any} /></MemoryRouter>);
    fireEvent.click(screen.getByRole('button', { name: 'Confirm preview' }));
    expect(mock.handleAction).toHaveBeenCalledExactlyOnceWith(button, current);
    expect(current).not.toHaveProperty('rows');
  });
  it('uses the freshly loaded record for availability and the exact flow invocation', () => {
    const stale = { pid: 'version-1', first_read_at: null };
    const runtime = { getContext: () => ({ record: stale }),
      getSchema: () => ({ modelCode: 'example' }),getDataSourceManager: () => null,
      getEvaluator: () => ({ evaluateCondition: (_expression: string, context: any) => !context.record.first_read_at }) };
    const button = { code: 'acknowledge', label: 'Acknowledge',disableWhen: '!record.first_read_at', action: { type: 'flow', steps: [] } };
    const block = { id: 'body-actions',blockType: 'toolbar',buttons: [button],record: stale };
    const view = render(<MemoryRouter><ToolbarBlockRenderer block={block as any} runtime={runtime as any} /></MemoryRouter>);
    expect(screen.getByRole('button')).toBeDisabled();
    const current = { ...stale, first_read_at: '2026-10-25T00:00:00Z' };
    view.rerender(<MemoryRouter><ToolbarBlockRenderer block={{...block,record: current} as any} runtime={runtime as any} /></MemoryRouter>);
    expect(screen.getByRole('button')).toBeEnabled();fireEvent.click(screen.getByRole('button'));
    expect(mock.handleAction).toHaveBeenCalledExactlyOnceWith(button, current);
  });
});
