import React from 'react';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { parse } from 'yaml';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import userEvent from '@testing-library/user-event';
import { I18nProvider } from '~/contexts/I18nContext';
import { TemplateGallery } from '../TemplateGallery';
import { CreateFromTemplateDialog } from '../CreateFromTemplateDialog';
import { preparePageCopyContent } from '../../services/page-manager/pageCopyContent';
const api = vi.hoisted(() => ({ getTemplates: vi.fn(), createPage: vi.fn() }));
vi.mock('~/plugins/core-designer/components/studio/services/page-manager/pageApi', () => ({ ...api, getPageByPageKey: vi.fn(), getVersionHistory: vi.fn(), createVersion: vi.fn(),
  updatePage: ('updatePage' in api ? api.updatePage : vi.fn()), getPageByPid: ('getPageByPid' in api ? api.getPageByPid : vi.fn()),
  publishPage: vi.fn(), unpublishPage: vi.fn(), rollbackToVersion: vi.fn(), compareVersions: vi.fn() }));
const dictionary = (locale: string) => parse(readFileSync(path.resolve(process.cwd(),
  `../platform/src/main/resources/i18n.${locale}.yaml`), 'utf8'));
const template = { pid: 'template-orders', name: 'Orders', title: 'Orders', kind: 'list',
  blocks: [{ id: 'order-block', type: 'table' }], layout: { columns: 2 }, modelCode: 'orders',
  profile: 'orders-profile', schemaVersion: 4, dataSources: { main: { modelCode: 'orders' } },
  extension: { customOption: true } };
function localized(locale: string, element: React.ReactNode) {
  return render(<I18nProvider initialLocale={locale} initialData={dictionary(locale)}>{element}</I18nProvider>);
}
beforeEach(() => {
  localStorage.clear(); vi.resetAllMocks();
  api.getTemplates.mockResolvedValue({ code: '0', data: [template,
    { ...template, pid: 'template-customer', name: 'Customers', kind: 'form' }] });
  api.createPage.mockResolvedValue({ code: '0', data: { pid: 'new-page' } });
});
afterEach(() => cleanup());
describe.each(['en-US', 'zh-CN'])('template workflow in %s', locale => {
  const callbacks = () => ({ onClose: vi.fn(), onSuccess: vi.fn() });
  async function configure(props = callbacks()) {
    localized(locale, <CreateFromTemplateDialog open {...props} />);
    fireEvent.click(await screen.findByTestId('template-card-template-orders'));
    return props;
  }
  it('localizes filters, trims the search and selects the actual template', async () => {
    const labels = dictionary(locale).designer_template; const onSelect = vi.fn();
    localized(locale, <TemplateGallery onSelect={onSelect} />);
    expect(await screen.findByTestId('template-card-template-orders')).toBeInTheDocument();
    expect(screen.getByRole('option', { name: labels.all })).toHaveValue('all');
    fireEvent.change(screen.getByTestId('template-kind-filter'), { target: { value: 'form' } });
    expect(screen.queryByTestId('template-card-template-orders')).not.toBeInTheDocument();
    fireEvent.change(screen.getByTestId('template-kind-filter'), { target: { value: 'all' } });
    fireEvent.change(screen.getByPlaceholderText(labels.search), { target: { value: '  ORDERS  ' } });
    expect(screen.getByTestId('template-card-template-orders')).toBeInTheDocument();
    expect(screen.queryByTestId('template-card-template-customer')).not.toBeInTheDocument();
    const card = screen.getByRole('button', { name: /Orders/ });
    card.focus();
    await userEvent.setup().keyboard('[Enter]');
    expect(onSelect).toHaveBeenCalledExactlyOnceWith(template);
  });
  it('shows localized loading and failure then retries through the API', async () => {
    const labels = dictionary(locale).designer_template;
    let resolve!: (value: unknown) => void;
    api.getTemplates.mockReturnValueOnce(new Promise(done => { resolve = done; }));
    localized(locale, <TemplateGallery onSelect={vi.fn()} />);
    expect(screen.getByTestId('template-gallery-loading')).toHaveTextContent(labels.loading);
    resolve({ code: '1' });
    expect(await screen.findByTestId('template-gallery-error')).toHaveTextContent(labels.load_failed);
    fireEvent.click(screen.getByRole('button', { name: labels.retry }));
    expect(await screen.findByTestId('template-card-template-orders')).toBeInTheDocument();
    expect(api.getTemplates).toHaveBeenCalledTimes(2);
  });
  it('rejects a malformed success payload instead of showing an empty gallery', async () => {
    const labels = dictionary(locale).designer_template;
    api.getTemplates.mockResolvedValue({ code: '0', data: {} });
    localized(locale, <TemplateGallery onSelect={vi.fn()} />);
    expect(await screen.findByTestId('template-gallery-error')).toHaveTextContent(labels.invalid_response);
    expect(screen.queryByTestId('template-empty')).not.toBeInTheDocument();
  });
  it('creates from a template without losing its format, model or layout', async () => {
    const labels = dictionary(locale).designer_template; const props = await configure();
    expect(screen.getByRole('heading', { name: labels.configure })).toBeInTheDocument();
    expect(screen.getByTestId('new-page-name-input')).toHaveValue(labels.copy_name.replace('{name}', 'Orders'));
    fireEvent.change(screen.getByTestId('new-page-name-input'), { target: { value: '  New orders  ' } });
    fireEvent.change(screen.getByTestId('new-page-key-input'), { target: { value: '  new_orders  ' } });
    fireEvent.click(screen.getByTestId('create-from-template-btn'));
    await waitFor(() => expect(props.onSuccess).toHaveBeenCalledExactlyOnceWith('new-page'));
    expect(api.createPage).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ name: 'New orders',
      title: 'New orders', pageKey: 'new_orders', kind: 'list', blocks: template.blocks,
      layout: template.layout, modelCode: 'orders', profile: template.profile,
      dataSources: template.dataSources, extension: template.extension, schemaVersion: 4 }));
    expect(props.onClose).toHaveBeenCalledTimes(1);
  });
  it('rejects a created page with no PID and keeps the dialog open', async () => {
    const labels = dictionary(locale).designer_page_dialog;
    api.createPage.mockResolvedValue({ code: '0', data: {} });
    const props = await configure(); fireEvent.click(screen.getByTestId('create-from-template-btn'));
    expect(await screen.findByText(labels.missing_pid)).toBeInTheDocument();
    expect(props.onSuccess).not.toHaveBeenCalled(); expect(props.onClose).not.toHaveBeenCalled();
  });
  it('prevents dismissal and step changes while creation is pending', async () => {
    const labels = dictionary(locale).designer_template;
    let resolve!: (value: unknown) => void;
    api.createPage.mockReturnValue(new Promise(done => { resolve = done; }));
    const props = await configure(); fireEvent.click(screen.getByTestId('create-from-template-btn'));
    fireEvent.click(screen.getByTestId('create-from-template-dialog').parentElement!);
    expect(props.onClose).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: labels.back_select })).toBeDisabled();
    expect(screen.getByTestId('new-page-name-input')).toBeDisabled();
    resolve({ code: '0', data: { pid: 'new-page' } });
    await waitFor(() => expect(props.onSuccess).toHaveBeenCalledExactlyOnceWith('new-page'));
  });
});

it('converts a stored v3 tree to real v4 blocks before copying', () => {
  const copied = preparePageCopyContent({ ...template, kind: 'list', schemaVersion: 3,
    blocks: [{ id: 'legacy-root', blockType: 'list', blocks: [{ id: 'legacy-table', blockType: 'table', blocks: [] }] }] });
  expect(copied.schemaVersion).toBe(4);
  expect(copied.blocks).toEqual([expect.objectContaining({ id: 'legacy-table', blockType: 'table' })]);
  expect(copied.extension).toEqual({ customOption: true, designerRootId: 'legacy-root' });
  expect(copied.modelCode).toBe('orders');
  expect(copied.layout).toEqual({ columns: 2 });
});
