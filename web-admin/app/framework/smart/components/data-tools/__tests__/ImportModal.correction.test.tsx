import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import { hasCompleteRowErrorContract, ImportModal } from '../ImportModal';

vi.mock('xlsx', () => ({
  read: () => ({ SheetNames: ['Import'], Sheets: { Import: {} } }),
  utils: {
    sheet_to_json: () => [{ Name: '', Code: 'L-002' }],
  },
}));

const jsonResponse = (data: unknown): Response =>
  ({
    ok: true,
    status: 200,
    json: async () => ({ code: '0', data }),
  }) as Response;

describe('ImportModal correction workflow', () => {
  const originalFetch = globalThis.fetch;

  beforeEach(() => {
    vi.restoreAllMocks();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('does not accept a partial terminal contract without inline row errors', () => {
    expect(
      hasCompleteRowErrorContract({
        totalRows: 2,
        successCount: 1,
        errorCount: 1,
        createdCount: 0,
        updatedCount: 1,
        errors: [],
        hasErrors: true,
        errorReportUrl: '/api/meta/excel/import/crm_lead/error-report/01KPARTIAL',
      }),
    ).toBe(false);
    expect(
      hasCompleteRowErrorContract({
        totalRows: 2,
        successCount: 1,
        errorCount: 1,
        createdCount: 0,
        updatedCount: 1,
        errors: [{ rowNumber: 3, message: 'No existing record matches code=MISSING' }],
        hasErrors: true,
      }),
    ).toBe(true);
  });

  it('offers an authorized correction workbook and accepts the corrected file in-place', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/field-meta')) {
        return jsonResponse([
          { code: 'name', displayName: 'Name' },
          { code: 'code', displayName: 'Lead code' },
        ]);
      }
      if (url.includes('/api/meta/excel/validate/crm_lead')) {
        return jsonResponse({
          totalRows: 1,
          validRows: 0,
          valid: false,
          errors: [
            {
              rowNumber: 2,
              fieldCode: 'name',
              message: 'Required field is missing',
            },
          ],
          warnings: [],
          taskId: '01KREPORT',
          errorReportUrl: '/api/meta/excel/import/crm_lead/error-report/01KREPORT',
        });
      }
      throw new Error(`Unexpected fetch: ${url}`);
    });
    globalThis.fetch = fetchMock as typeof fetch;

    render(
      <I18nProvider initialData={{}} initialLocale="zh-CN">
        <ImportModal open onClose={vi.fn()} modelCode="crm_lead" />
      </I18nProvider>,
    );

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    const brokenFile = new File(['broken'], 'lead-import.xlsx', {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    });
    fireEvent.change(input, { target: { files: [brokenFile] } });

    expect(await screen.findByTestId('import-download-error-report')).toBeEnabled();
    expect(screen.getByTestId('import-upload-correction')).toBeEnabled();
    expect(screen.getByTestId('import-submit')).toBeDisabled();

    const correctedFile = new File(['fixed'], 'crm_lead-insert-import-errors.xlsx', {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    });
    fireEvent.click(screen.getByTestId('import-upload-correction'));
    fireEvent.change(input, { target: { files: [correctedFile] } });

    await waitFor(() => {
      const validationCalls = fetchMock.mock.calls.filter(([url]) =>
        String(url).includes('/api/meta/excel/validate/crm_lead'),
      );
      expect(validationCalls).toHaveLength(2);
      expect(validationCalls.every(([url]) => String(url).includes('locale=zh-CN'))).toBe(true);
    });
  });

  it('keeps row errors visible when report generation is unavailable', async () => {
    globalThis.fetch = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/field-meta')) return jsonResponse([]);
      if (url.includes('/api/meta/excel/validate/crm_lead')) {
        return jsonResponse({
          totalRows: 1,
          validRows: 0,
          valid: false,
          errors: [{ rowNumber: 2, fieldCode: 'name', message: 'Required field is missing' }],
          warnings: [],
          errorReportFailed: true,
        });
      }
      throw new Error(`Unexpected fetch: ${url}`);
    }) as typeof fetch;

    render(
      <I18nProvider initialData={{}} initialLocale="zh-CN">
        <ImportModal open onClose={vi.fn()} modelCode="crm_lead" />
      </I18nProvider>,
    );

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(input, {
      target: {
        files: [
          new File(['broken'], 'lead-import.xlsx', {
            type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
          }),
        ],
      },
    });

    expect(await screen.findByTestId('import-error-report-unavailable')).toBeVisible();
    expect(screen.getByText(/第 2 行/)).toBeVisible();
    expect(screen.queryByTestId('import-download-error-report')).not.toBeInTheDocument();
  });

  it('turns a failed execution into an explicit retry without discarding the validated file', async () => {
    let importAttempts = 0;
    globalThis.fetch = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/field-meta')) return jsonResponse([]);
      if (url.includes('/api/meta/excel/validate/crm_lead')) {
        return jsonResponse({
          totalRows: 1,
          validRows: 1,
          valid: true,
          errors: [],
          warnings: [],
        });
      }
      if (url.includes('/api/meta/excel/import/crm_lead')) {
        importAttempts += 1;
        if (importAttempts === 1) {
          return {
            ok: false,
            status: 503,
            json: async () => ({ code: '500', message: 'Temporary import failure' }),
          } as Response;
        }
        return jsonResponse({
          totalRows: 1,
          successCount: 1,
          errorCount: 0,
          createdCount: 1,
          updatedCount: 0,
          errors: [],
          hasErrors: false,
          taskId: '01KSYNCREPORT',
          asyncTask: false,
        });
      }
      throw new Error(`Unexpected fetch: ${url}`);
    }) as typeof fetch;

    render(
      <I18nProvider initialData={{}} initialLocale="zh-CN">
        <ImportModal open onClose={vi.fn()} modelCode="crm_lead" />
      </I18nProvider>,
    );

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(input, {
      target: {
        files: [
          new File(['valid'], 'lead-import.xlsx', {
            type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
          }),
        ],
      },
    });

    const submit = await screen.findByTestId('import-submit');
    await waitFor(() => expect(submit).toBeEnabled());
    fireEvent.click(submit);
    expect(await screen.findByText('Temporary import failure')).toBeVisible();
    expect(screen.getByTestId('import-submit')).toHaveTextContent('重试导入');

    fireEvent.click(screen.getByTestId('import-submit'));
    expect(await screen.findByTestId('import-result-created')).toHaveTextContent('1');
    expect(importAttempts).toBe(2);
  });

  it('requests owner-scoped cancellation and exposes the same file for retry', async () => {
    globalThis.fetch = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/field-meta')) return jsonResponse([]);
      if (url.includes('/api/meta/excel/validate/crm_lead')) {
        return jsonResponse({
          totalRows: 10_000,
          validRows: 10_000,
          valid: true,
          errors: [],
          warnings: [],
        });
      }
      if (url.endsWith('/api/meta/excel/import/crm_lead?mode=insert&locale=zh-CN&skipErrors=true')) {
        return jsonResponse({ taskId: '01KCANCEL', totalRows: 10_000, asyncTask: true });
      }
      if (url.includes('/api/meta/excel/import/crm_lead/cancel/01KCANCEL')) {
        return jsonResponse({ taskId: '01KCANCEL', status: 'running' });
      }
      if (url.includes('/api/meta/excel/import/crm_lead/status/01KCANCEL')) {
        return jsonResponse({
          taskId: '01KCANCEL',
          status: 'cancelled',
          totalRows: 10_000,
          processedRows: 500,
        });
      }
      throw new Error(`Unexpected fetch: ${url}`);
    }) as typeof fetch;

    render(
      <I18nProvider initialData={{}} initialLocale="zh-CN">
        <ImportModal open onClose={vi.fn()} modelCode="crm_lead" />
      </I18nProvider>,
    );

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(input, {
      target: {
        files: [
          new File(['valid'], 'lead-10k.xlsx', {
            type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
          }),
        ],
      },
    });
    const submit = await screen.findByTestId('import-submit');
    await waitFor(() => expect(submit).toBeEnabled());
    fireEvent.click(submit);

    fireEvent.click(await screen.findByTestId('import-cancel-task'));
    expect(await screen.findByRole('status')).toHaveTextContent('正在取消导入');
    expect(await screen.findByText(/导入已取消/)).toBeVisible();
    expect(screen.getByTestId('import-submit')).toHaveTextContent('重试导入');
  });
});
