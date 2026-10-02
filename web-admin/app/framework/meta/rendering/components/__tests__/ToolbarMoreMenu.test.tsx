import React from 'react';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { parse } from 'yaml';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import { ToolbarMoreMenu } from '../ToolbarMoreMenu';
const mocks = vi.hoisted(() => ({
  getPublished: vi.fn(),
  generate: vi.fn(),
  error: vi.fn(),
  success: vi.fn(),
}));
vi.mock('~/shared/services/reportTemplateService', () => ({ reportTemplateService: mocks }));
vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({ showErrorToast: mocks.error, showSuccessToast: mocks.success }),
}));
const conditions = [{ field: 'status', operator: 'EQ', value: 'draft' }];
const template = {
  pid: 'report-a',
  code: 'orders_pdf',
  name: 'Orders',
  category: 'orders',
  outputFormat: 'pdf',
};
let fetchMock: ReturnType<typeof vi.fn>;
let downloads: { name: string; href: string }[];
beforeEach(() => {
  localStorage.clear();
  vi.clearAllMocks();
  downloads = [];
  mocks.getPublished.mockResolvedValue({ code: '0', data: [template] });
  mocks.generate.mockResolvedValue(new Blob(['report bytes']));
  fetchMock = vi
    .fn()
    .mockResolvedValue({
      ok: true,
      json: async () => ({ code: '0', data: { downloadUrl: '/files/export' } }),
    });
  vi.stubGlobal('fetch', fetchMock);
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function () {
    downloads.push({ name: this.download, href: this.getAttribute('href')! });
  });
  vi.stubGlobal(
    'URL',
    class extends URL {
      static createObjectURL = vi.fn(() => 'blob:report');
      static revokeObjectURL = vi.fn();
    },
  );
  vi.spyOn(window, 'print').mockImplementation(() => {});
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});
describe.each(['en-US', 'zh-CN'])('toolbar more menu in %s', (locale) => {
  const dict = () =>
    parse(
      readFileSync(
        path.resolve(process.cwd(), `../platform/src/main/resources/i18n.${locale}.yaml`),
        'utf8',
      ),
    );
  const mount = (modelCode = 'orders', onImport = vi.fn()) =>
    render(
      <I18nProvider initialLocale={locale} initialData={dict()}>
        <ToolbarMoreMenu modelCode={modelCode} filters={conditions} onImport={onImport} />
      </I18nProvider>,
    );
  const open = () => fireEvent.click(screen.getByTestId('toolbar-more-menu'));
  it('localizes labels and keeps import and print actions reachable', async () => {
    const onImport = vi.fn();
    mount('orders', onImport);
    open();
    const terms = dict().toolbar_more;
    expect(screen.getByTestId('toolbar-more-menu')).toHaveAccessibleName(terms.more);
    for (const [id, key] of [
      ['print', 'print'],
      ['import', 'import'],
      ['export-excel', 'export_excel'],
      ['export-csv', 'export_csv'],
    ]) {
      expect(screen.getByTestId(`more-menu-${id}`)).toHaveTextContent(terms[key]);
    }
    fireEvent.click(screen.getByTestId('more-menu-import'));
    expect(onImport).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('more-menu-print')).not.toBeInTheDocument();
    open();
    fireEvent.click(screen.getByTestId('more-menu-print'));
    expect(window.print).toHaveBeenCalledTimes(1);
  });
  it.each(['rejected', 'business-error', 'invalid-data'])(
    'shows a localized report load failure for %s',
    async (kind) => {
      if (kind === 'rejected') mocks.getPublished.mockRejectedValue(new Error('technical failure'));
      else
        mocks.getPublished.mockResolvedValue({
          code: kind === 'business-error' ? 'DENIED' : '0',
          data: {},
        });
      mount();
      open();
      expect(await screen.findByRole('alert')).toHaveTextContent(dict().toolbar_more.load_failed);
      expect(screen.queryByTestId('more-menu-report-orders_pdf')).not.toBeInTheDocument();
      expect(screen.queryByText(dict().toolbar_more.no_reports)).not.toBeInTheDocument();
    },
  );
  it('distinguishes empty reports from loading and failure', async () => {
    let resolve!: (value: unknown) => void;
    mocks.getPublished.mockReturnValue(
      new Promise((r) => {
        resolve = r;
      }),
    );
    mount();
    open();
    expect(screen.getByRole('status')).toHaveTextContent(dict().toolbar_more.loading_reports);
    resolve({ code: '0', data: [] });
    expect(await screen.findByText(dict().toolbar_more.no_reports)).toBeVisible();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
  it('reloads report templates for a changed model without showing the previous model', async () => {
    const view = mount();
    open();
    expect(await screen.findByTestId('more-menu-report-orders_pdf')).toBeVisible();
    mocks.getPublished.mockResolvedValue({
      code: '0',
      data: [{ ...template, code: 'products_pdf', category: 'products' }],
    });
    view.rerender(
      <I18nProvider initialLocale={locale} initialData={dict()}>
        <ToolbarMoreMenu modelCode="products" filters={conditions} onImport={vi.fn()} />
      </I18nProvider>,
    );
    expect(await screen.findByTestId('more-menu-report-products_pdf')).toBeVisible();
    expect(screen.queryByTestId('more-menu-report-orders_pdf')).not.toBeInTheDocument();
  });
  it.each([
    ['excel', 'xlsx', 'excel'],
    ['csv', 'csv', 'csv'],
  ])('exports %s with exact filters and filename', async (button, ext, format) => {
    mount();
    open();
    fireEvent.click(screen.getByTestId(`more-menu-export-${button}`));
    await waitFor(() =>
      expect(downloads).toEqual([{ name: `orders_export.${ext}`, href: '/files/export' }]),
    );
    expect(fetchMock).toHaveBeenCalledExactlyOnceWith(
      '/api/dynamic/orders/export',
      expect.objectContaining({
        method: 'post',
        body: JSON.stringify({ scope: 'filtered', format, conditions }),
      }),
    );
    expect(mocks.error).not.toHaveBeenCalled();
  });
  it.each(['http', 'business', 'missing-url'])(
    'fails export visibly without a download for %s',
    async (kind) => {
      fetchMock.mockResolvedValue({
        ok: kind !== 'http',
        json: async () => ({
          code: kind === 'business' ? 'ERROR' : '0',
          desc: 'technical failure',
          data: {},
        }),
      });
      mount();
      open();
      fireEvent.click(screen.getByTestId('more-menu-export-csv'));
      await waitFor(() =>
        expect(mocks.error).toHaveBeenCalledExactlyOnceWith(dict().toolbar_more.export_failed),
      );
      expect(downloads).toEqual([]);
      expect(mocks.success).not.toHaveBeenCalled();
    },
  );
  it('generates the selected report with filters and revokes its download URL', async () => {
    mount();
    open();
    fireEvent.click(await screen.findByTestId('more-menu-report-orders_pdf'));
    await waitFor(() =>
      expect(mocks.success).toHaveBeenCalledExactlyOnceWith(
        dict().toolbar_more.report_generated.replace('{name}', 'Orders'),
      ),
    );
    expect(mocks.generate).toHaveBeenCalledExactlyOnceWith('orders_pdf', { filters: conditions });
    expect(downloads).toEqual([{ name: 'Orders.pdf', href: 'blob:report' }]);
    expect(URL.revokeObjectURL).toHaveBeenCalledExactlyOnceWith('blob:report');
  });
  it('reports generation failure in the current locale without success or download', async () => {
    mocks.generate.mockRejectedValue(new Error('technical failure'));
    mount();
    open();
    fireEvent.click(await screen.findByTestId('more-menu-report-orders_pdf'));
    await waitFor(() =>
      expect(mocks.error).toHaveBeenCalledExactlyOnceWith(dict().toolbar_more.report_failed),
    );
    expect(downloads).toEqual([]);
    expect(mocks.success).not.toHaveBeenCalled();
  });
});
