import { MemoryRouter } from 'react-router';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToolbarBlockRenderer } from '../ToolbarBlockRenderer';
import { ExpressionEvaluator } from '../../../runtime/expression/evaluator';

const mock = vi.hoisted(() => ({ handleAction: vi.fn().mockResolvedValue(undefined) }));
vi.mock('~/framework/meta/hooks/useActionHandler', () => ({ useActionHandler: () => ({ handleAction: mock.handleAction, loading: false }) }));
vi.mock('~/contexts/AuthContext', () => ({ useAuth: () => ({ hasPermission: () => true }) }));
vi.mock('~/contexts/ToastContext', () => ({ useToastContext: () => ({}) }));

describe('record-bound detail body toolbar', () => {
  beforeEach(() => mock.handleAction.mockClear());
  it('observes a newer same-record command result without retaining the original disabled state or version', () => {
    const original = { pid: 'task-a', row_version: 1, matchingReady: false };
    let form = { ...original };
    const listeners = new Set<() => void>();
    const runtime = { getContext: () => ({ record: original, form }),
      subscribeContext: (listener: () => void) => { listeners.add(listener); return () => listeners.delete(listener); },
      getSchema: () => ({ modelCode: 'example' }), getDataSourceManager: () => null,
      getEvaluator: () => new ExpressionEvaluator() };
    const button = { code: 'send', label: 'Send', disableWhen: '${form.matchingReady !== true}', action: { type: 'flow', steps: [] } };
    render(<MemoryRouter><ToolbarBlockRenderer block={{ id: 'actions', blockType: 'toolbar', record: original, buttons: [button] } as any} runtime={runtime as any} /></MemoryRouter>);
    expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled();
    act(() => { form = { ...form, row_version: 2, matchingReady: true }; listeners.forEach(listener => listener()); });
    expect(screen.getByRole('button', { name: 'Send' })).toBeEnabled();
    fireEvent.click(screen.getByRole('button', { name: 'Send' }));
    expect(mock.handleAction).toHaveBeenCalledExactlyOnceWith(button, form);
    expect(original).toEqual({ pid: 'task-a', row_version: 1, matchingReady: false });
  });
  it('does not replace a newer bound parent with older or unrelated form state', () => {
    const original = { pid: 'task-a', row_version: 7, matchingReady: false };
    for (const form of [{ ...original, row_version: 6, matchingReady: true },
      { pid: 'other-task', row_version: 99, matchingReady: true }]) {
      const runtime = { getContext: () => ({ record: original, form }),
        getSchema: () => ({ modelCode: 'example' }), getDataSourceManager: () => null,
        getEvaluator: () => new ExpressionEvaluator() };
      const view = render(<MemoryRouter><ToolbarBlockRenderer block={{ id: 'actions', blockType: 'toolbar', record: original,
        buttons: [{ code: 'send', label: 'Send', disableWhen: '${form.matchingReady !== true}', action: { type: 'flow', steps: [] } }] } as any} runtime={runtime as any} /></MemoryRouter>);
      expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled(); view.unmount();
    }
  });
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
