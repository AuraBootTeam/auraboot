import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { AsyncTaskProgressModal, parseProgressMessage } from '../AsyncTaskProgressModal';

describe('parseProgressMessage', () => {
  it('parses progress json', () => {
    expect(parseProgressMessage('{"processed":10,"total":20,"ok":8,"failed":1,"skipped":1}')).toEqual({
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
    expect(screen.getByText(/导入完成|Completed/)).toBeTruthy();
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
  it('empty file: total 0 message', () => {
    render(
      <AsyncTaskProgressModal
        task={{
          status: 'completed',
          progress: 100,
          resultData: { totalRows: 0, importedRows: 0, skippedRows: 0, failedRows: 0, failures: [] },
        }}
        onClose={() => {}}
        onBackground={() => {}}
      />,
    );
    expect(screen.getByText(/未导入任何数据|No rows/)).toBeTruthy();
  });
});
