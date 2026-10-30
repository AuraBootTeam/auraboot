import { MemoryRouter } from 'react-router';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ToolbarBlockRenderer } from '../ToolbarBlockRenderer';

const mock = vi.hoisted(() => ({ handleAction: vi.fn().mockResolvedValue(undefined) }));
vi.mock('~/framework/meta/hooks/useActionHandler', () => ({ useActionHandler: () => ({ handleAction: mock.handleAction, loading: false }) }));
vi.mock('~/contexts/AuthContext', () => ({ useAuth: () => ({ hasPermission: () => true }) }));
vi.mock('~/contexts/ToastContext', () => ({ useToastContext: () => ({}) }));

describe('record-bound detail body toolbar', () => {
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
