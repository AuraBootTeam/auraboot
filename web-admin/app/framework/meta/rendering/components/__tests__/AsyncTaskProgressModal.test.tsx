import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { AsyncTaskProgressModal, parseProgressMessage } from '../AsyncTaskProgressModal';

describe('parseProgressMessage', () => {
  it('parses progress json', () => {
    expect(
      parseProgressMessage('{"processed":10,"total":20,"ok":8,"failed":1,"skipped":1}'),
    ).toEqual({
      processed: 10,
      total: 20,
      ok: 8,
      failed: 1,
      skipped: 1,
    });
  });
  it('returns null for non-json text', () => {
    expect(parseProgressMessage('Starting')).toBeNull();
  });
});

describe('AsyncTaskProgressModal', () => {
  it('running: shows determinate progress + live counts', () => {
    render(
      <AsyncTaskProgressModal
        task={{
          status: 'running',
          progress: 62,
          progressMessage: '{"processed":22310,"total":35924,"ok":22180,"failed":12,"skipped":118}',
        }}
        onClose={() => {}}
        onBackground={() => {}}
      />,
    );
    expect(screen.getByText(/62%/)).toBeTruthy();
    expect(screen.getByText(/22,?180/)).toBeTruthy(); // ok count
    expect(screen.getByText(/35,?924/)).toBeTruthy(); // total
  });
  it('completed: shows summary + copyable failures', () => {
    render(
      <AsyncTaskProgressModal
        task={{
          status: 'completed',
          taskLabel: '数据导入',
          progress: 100,
          resultData: {
            totalRows: 3,
            importedRows: 2,
            skippedRows: 0,
            failedRows: 1,
            failures: [{ row: 3, reason: '重复料号(A)' }],
          },
        }}
        onClose={() => {}}
        onBackground={() => {}}
      />,
    );
    expect(screen.getByText(/数据导入已完成|Completed/)).toBeTruthy();
    expect(screen.getByText(/重复料号/)).toBeTruthy();
    expect(screen.getByTestId('copy-failures')).toBeTruthy();
  });
  it('completed: copy-failures writes to clipboard', () => {
    const writeText = vi.fn();
    Object.assign(navigator, { clipboard: { writeText } });
    render(
      <AsyncTaskProgressModal
        task={{
          status: 'completed',
          taskLabel: '数据导入',
          progress: 100,
          resultData: {
            totalRows: 3,
            importedRows: 2,
            skippedRows: 0,
            failedRows: 1,
            failures: [{ row: 3, reason: '重复料号(A)' }],
          },
        }}
        onClose={() => {}}
        onBackground={() => {}}
      />,
    );
    fireEvent.click(screen.getByTestId('copy-failures'));
    expect(writeText).toHaveBeenCalledWith(expect.stringContaining('重复料号(A)'));
  });
  it('failed: shows errorMessage', () => {
    render(
      <AsyncTaskProgressModal
        task={{ status: 'failed', progress: 0, errorMessage: '文件解析失败' }}
        onClose={() => {}}
        onBackground={() => {}}
      />,
    );
    expect(screen.getByText(/文件解析失败/)).toBeTruthy();
  });
  it('completed generic command: renders declarative metrics without import-only zero counts', () => {
    render(
      <AsyncTaskProgressModal
        task={{
          status: 'completed',
          taskLabel: '立即增量同步',
          locale: 'zh-CN',
          progress: 100,
          resultData: { processedRows: 154, masterCreated: 151, failedRows: 0 },
          presentation: {
            title: { 'zh-CN': '金蝶物料增量同步', 'en-US': 'Kingdee incremental sync' },
            completedMessage: { 'zh-CN': '金蝶物料同步完成', 'en-US': 'Kingdee sync completed' },
            metrics: [
              { field: 'processedRows', label: { 'zh-CN': '处理行数', 'en-US': 'Processed' } },
              {
                field: 'masterCreated',
                label: { 'zh-CN': '新增物料', 'en-US': 'Created' },
                tone: 'success',
              },
              {
                field: 'failedRows',
                label: { 'zh-CN': '失败行数', 'en-US': 'Failed' },
                tone: 'danger',
              },
            ],
          },
        }}
        onClose={() => {}}
        onBackground={() => {}}
      />,
    );
    expect(screen.getByText('金蝶物料增量同步')).toBeTruthy();
    expect(screen.getByText('金蝶物料同步完成')).toBeTruthy();
    expect(screen.getByText(/处理行数/).textContent).toContain('154');
    expect(screen.queryByText(/总行数: 0/)).toBeNull();
  });
  it('completed import with declarative metrics still exposes row-level failures', () => {
    render(
      <AsyncTaskProgressModal
        task={{
          status: 'completed',
          locale: 'zh-CN',
          resultData: {
            totalRows: 2,
            importedRows: 1,
            skippedRows: 1,
            failedRows: 1,
            failures: [{ row: 3, reason: 'Customer name is required' }],
          },
          presentation: {
            title: { 'zh-CN': '客户公海批量导入' },
            metrics: [
              { field: 'importedRows', label: { 'zh-CN': '已导入' }, tone: 'success' },
              { field: 'failedRows', label: { 'zh-CN': '失败' }, tone: 'danger' },
            ],
          },
        }}
        onClose={() => {}}
        onBackground={() => {}}
      />,
    );

    expect(screen.getByText(/Customer name is required/)).toBeTruthy();
    expect(screen.getByTestId('copy-failures')).toBeTruthy();
  });
  it('cancelled: is terminal and exposes cancellation state', () => {
    render(
      <AsyncTaskProgressModal
        task={{ status: 'cancelled', taskLabel: '全量对账', progress: 20 }}
        onClose={() => {}}
        onBackground={() => {}}
      />,
    );
    expect(screen.getByTestId('async-task-modal-cancelled')).toBeTruthy();
    expect(screen.getByText(/任务已取消|Cancelled/)).toBeTruthy();
  });
  it('empty file: total 0 message', () => {
    render(
      <AsyncTaskProgressModal
        task={{
          status: 'completed',
          taskLabel: '数据导入',
          progress: 100,
          resultData: {
            totalRows: 0,
            importedRows: 0,
            skippedRows: 0,
            failedRows: 0,
            failures: [],
          },
        }}
        onClose={() => {}}
        onBackground={() => {}}
      />,
    );
    expect(screen.getByText(/未导入任何数据|No rows/)).toBeTruthy();
  });
});

describe('English async task status UX', () => {
  const callbacks = () => ({ onClose: vi.fn(), onBackground: vi.fn() });
  it('running localizes counts and preserves background action', () => {
    const props = callbacks();
    render(
      <AsyncTaskProgressModal
        {...props}
        task={{
          status: 'running',
          locale: 'en-US',
          progress: 40,
          progressMessage: '{"processed":4,"total":10,"ok":3,"failed":1,"skipped":0}',
        }}
      />,
    );
    expect(screen.getByText('Background task')).toBeTruthy();
    for (const label of ['Total:', 'Processed:', 'Succeeded:', 'Failed:', 'Skipped:'])
      expect(screen.getByText(label, { exact: false })).toBeTruthy();
    expect(screen.getByRole('progressbar').getAttribute('aria-valuenow')).toBe('40');
    fireEvent.click(screen.getByRole('button', { name: 'Run in background' }));
    expect(props.onBackground).toHaveBeenCalledOnce();
    expect(props.onClose).not.toHaveBeenCalled();
  });
  it('pending rerenders in a changed locale', () => {
    const props = callbacks();
    const view = render(
      <AsyncTaskProgressModal {...props} task={{ status: 'pending', locale: 'en-US' }} />,
    );
    expect(screen.getByText('Background task in progress\u2026')).toBeTruthy();
    view.rerender(
      <AsyncTaskProgressModal {...props} task={{ status: 'pending', locale: 'zh-CN' }} />,
    );
    expect(screen.queryByText('Background task')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Run in background' })).toBeNull();
  });
  it('completed import localizes rows, expansion and clipboard', () => {
    const writeText = vi.fn();
    Object.assign(navigator, { clipboard: { writeText } });
    render(
      <AsyncTaskProgressModal
        {...callbacks()}
        task={{
          status: 'completed',
          locale: 'en-US',
          taskLabel: 'Import',
          resultData: {
            totalRows: 4,
            importedRows: 2,
            skippedRows: 0,
            failedRows: 2,
            failures: [
              { row: 3, reason: 'Duplicate material' },
              { row: 4, reason: 'Missing name' },
            ],
          },
        }}
      />,
    );
    expect(screen.getByText('Import completed')).toBeTruthy();
    expect(screen.getByText('Row 3 \u2014 Duplicate material')).toBeTruthy();
    expect(screen.queryByText('Row 4 \u2014 Missing name')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Show failure details (2)' }));
    expect(screen.getByText('Row 4 \u2014 Missing name')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Copy' }));
    expect(writeText).toHaveBeenCalledWith(
      'Row 3 \u2014 Duplicate material\nRow 4 \u2014 Missing name',
    );
    fireEvent.click(screen.getByRole('button', { name: 'Hide failure details' }));
    expect(screen.queryByText('Row 4 \u2014 Missing name')).toBeNull();
  });
  it('empty and generic completion preserve close action', () => {
    const props = callbacks();
    const view = render(
      <AsyncTaskProgressModal
        {...props}
        task={{
          status: 'completed',
          locale: 'en-US',
          resultData: { totalRows: 0, importedRows: 0, skippedRows: 0, failedRows: 0 },
        }}
      />,
    );
    expect(screen.getByText('No rows imported')).toBeTruthy();
    view.rerender(
      <AsyncTaskProgressModal {...props} task={{ status: 'completed', locale: 'en-US' }} />,
    );
    expect(screen.getByText('Task completed successfully.')).toBeTruthy();
    fireEvent.click(screen.getByText('Close', { selector: 'button' }));
    expect(props.onClose).toHaveBeenCalledOnce();
  });
  it('declared titles, metrics and boolean values remain localized', () => {
    render(
      <AsyncTaskProgressModal
        {...callbacks()}
        task={{
          status: 'completed',
          locale: 'en-US',
          resultData: { ready: true, accepted: false },
          presentation: {
            title: { 'en-US': 'Sync status' },
            completedMessage: { 'en-US': 'Sync finished' },
            metrics: [
              { field: 'ready', label: { 'en-US': 'Ready' } },
              { field: 'accepted', label: { 'en-US': 'Accepted' } },
            ],
          },
        }}
      />,
    );
    for (const text of ['Sync status', 'Sync finished', 'Yes', 'No'])
      expect(screen.getByText(text)).toBeTruthy();
  });
  it('failed fallback preserves original error messages', () => {
    const props = callbacks();
    const view = render(
      <AsyncTaskProgressModal {...props} task={{ status: 'failed', locale: 'en-US' }} />,
    );
    expect(screen.getByText('Task failed')).toBeTruthy();
    expect(screen.getByText('Unknown error')).toBeTruthy();
    view.rerender(
      <AsyncTaskProgressModal
        {...props}
        task={{ status: 'failed', locale: 'en-US', errorMessage: 'Original failure' }}
      />,
    );
    expect(screen.getByText('Original failure')).toBeTruthy();
    expect(screen.queryByText('Unknown error')).toBeNull();
  });
  it('cancelled is terminal and can be closed', () => {
    const props = callbacks();
    render(<AsyncTaskProgressModal {...props} task={{ status: 'cancelled', locale: 'en-US' }} />);
    expect(screen.getByText('Task cancelled')).toBeTruthy();
    expect(
      screen.getByText('The task stopped. Unfinished steps will not be processed.'),
    ).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Run in background' })).toBeNull();
    fireEvent.click(screen.getByText('Close', { selector: 'button' }));
    expect(props.onClose).toHaveBeenCalledOnce();
  });
});
