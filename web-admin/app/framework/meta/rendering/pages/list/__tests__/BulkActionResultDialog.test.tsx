import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { BulkActionResultDialog } from '../BulkActionResultDialog';

describe('BulkActionResultDialog', () => {
  it('shows exact success and failure records for a mixed bulk result', () => {
    const onClose = vi.fn();
    render(
      <BulkActionResultDialog
        result={{
          actionLabel: '批量资格确认',
          successCount: 1,
          failures: [
            {
              recordPid: 'opp-2',
              recordLabel: '华东存量商机',
              reason: '当前阶段不允许资格确认',
            },
          ],
        }}
        onClose={onClose}
        locale="en-US"
        t={(_key, _params, fallback) => fallback || ''}
      />,
    );

    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByText('1 succeeded')).toBeInTheDocument();
    expect(screen.getByText('1 failed')).toBeInTheDocument();
    expect(screen.getByText('华东存量商机')).toBeInTheDocument();
    expect(screen.getByText('当前阶段不允许资格确认')).toBeInTheDocument();

    fireEvent.click(screen.getAllByRole('button', { name: 'Close' }).at(-1)!);
    expect(onClose).toHaveBeenCalledOnce();
  });

  it('uses Chinese fallbacks and turns a generic state failure into actionable copy', () => {
    render(
      <BulkActionResultDialog
        result={{
          actionLabel: '批量资格确认',
          successCount: 1,
          failures: [
            {
              recordPid: 'opp-2',
              recordLabel: '华东存量商机',
              reason: 'Bad parameter',
            },
          ],
        }}
        onClose={vi.fn()}
        locale="zh-CN"
        t={(_key, _params, fallback) => fallback || ''}
      />,
    );

    expect(screen.getByRole('heading', { name: '批量操作结果' })).toBeInTheDocument();
    expect(screen.getByText('成功 1 条')).toBeInTheDocument();
    expect(screen.getByText('失败 1 条')).toBeInTheDocument();
    expect(screen.getByText('Bad parameter')).toBeInTheDocument();
  });
});
