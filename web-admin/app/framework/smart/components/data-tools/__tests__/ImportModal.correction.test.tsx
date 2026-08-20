import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import { ImportModal } from '../ImportModal';

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
});
