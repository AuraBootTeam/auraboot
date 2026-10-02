import React from 'react';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { parse } from 'yaml';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import { ClonePageDialog } from '../ClonePageDialog';
import { SaveAsTemplateDialog } from '../SaveAsTemplateDialog';

const api = vi.hoisted(() => ({ getPageByPid: vi.fn(), createPage: vi.fn(), updatePage: vi.fn() }));
vi.mock('~/plugins/core-designer/components/studio/services/page-manager/pageApi', () => api);
const dictionary = (locale: string) => parse(readFileSync(
  path.resolve(process.cwd(), `../platform/src/main/resources/i18n.${locale}.yaml`), 'utf8',
));
function localized(locale: string, element: React.ReactNode) {
  return render(<I18nProvider initialLocale={locale} initialData={dictionary(locale)}>{element}</I18nProvider>);
}
const source = { pid: 'source-pid', kind: 'list', blocks: [{ id: 'source-block', type: 'field' }], layout: { columns: 2 } };
beforeEach(() => {
  localStorage.clear(); vi.resetAllMocks();
  api.getPageByPid.mockResolvedValue({ code: '0', data: source });
  api.createPage.mockResolvedValue({ code: '0', data: { pid: 'new-pid' } });
  api.updatePage.mockResolvedValue({ code: '0' });
});
afterEach(() => cleanup());

describe.each(['en-US', 'zh-CN'])('page dialogs in %s', locale => {
  const callbacks = () => ({ onClose: vi.fn(), onSuccess: vi.fn() });
  function clone(props = callbacks()) {
    localized(locale, <ClonePageDialog open sourcePage={{ id: 'source-pid', title: 'Orders' }} {...props} />);
    return props;
  }
  function save(props = callbacks()) {
    localized(locale, <SaveAsTemplateDialog open pagePid="existing-pid" currentName="Orders" {...props} />);
    return props;
  }
  it('localizes clone controls and preserves source layout and edited values', async () => {
    const labels = dictionary(locale).designer_page_dialog; const props = clone();
    expect(screen.getByRole('heading', { name: labels.clone })).toBeInTheDocument();
    expect(screen.getByTestId('clone-name-input')).toHaveValue(labels.clone_default.replace('{name}', 'Orders'));
    expect(screen.getByPlaceholderText(labels.name_hint)).toBeInTheDocument();
    expect(screen.getByText(labels.key_hint)).toBeInTheDocument();
    fireEvent.change(screen.getByTestId('clone-name-input'), { target: { value: '  New orders  ' } });
    fireEvent.change(screen.getByTestId('clone-key-input'), { target: { value: '  new_orders  ' } });
    fireEvent.click(screen.getByTestId('clone-confirm-btn'));
    await waitFor(() => expect(props.onSuccess).toHaveBeenCalledExactlyOnceWith('new-pid'));
    expect(api.getPageByPid).toHaveBeenCalledExactlyOnceWith('source-pid');
    expect(api.createPage).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
      name: 'New orders', title: 'New orders', pageKey: 'new_orders', blocks: source.blocks,
      layout: source.layout, kind: 'list', metaInfo: { componentCount: 1, clonedFrom: 'source-pid' },
    }));
    expect(props.onClose).toHaveBeenCalledTimes(1);
  });
  it('shows the actual source API message and does not create or close on failure', async () => {
    api.getPageByPid.mockResolvedValue({ code: '1', message: 'Permission denied' });
    const props = clone(); fireEvent.click(screen.getByTestId('clone-confirm-btn'));
    expect(await screen.findByText('Permission denied')).toBeInTheDocument();
    expect(api.createPage).not.toHaveBeenCalled(); expect(props.onClose).not.toHaveBeenCalled();
    expect(props.onSuccess).not.toHaveBeenCalled();
  });
  it('rejects a success response with no created page identifier', async () => {
    const labels = dictionary(locale).designer_page_dialog;
    api.createPage.mockResolvedValue({ code: '0', data: {} });
    const props = clone(); fireEvent.click(screen.getByTestId('clone-confirm-btn'));
    expect(await screen.findByText(labels.missing_pid)).toBeInTheDocument();
    expect(props.onSuccess).not.toHaveBeenCalled(); expect(props.onClose).not.toHaveBeenCalled();
  });
  it('localizes template controls and sends edited template metadata', async () => {
    const labels = dictionary(locale).designer_page_dialog; const props = save();
    expect(screen.getByRole('heading', { name: labels.save_template })).toBeInTheDocument();
    expect(screen.getByTestId('template-name-input')).toHaveValue(labels.template_default.replace('{name}', 'Orders'));
    expect(screen.getByPlaceholderText(labels.category_hint)).toBeInTheDocument();
    fireEvent.change(screen.getByTestId('template-name-input'), { target: { value: '  Order template  ' } });
    fireEvent.change(screen.getByTestId('template-category-input'), { target: { value: '  CRM  ' } });
    fireEvent.click(screen.getByRole('button', { name: labels.save }));
    await waitFor(() => expect(props.onSuccess).toHaveBeenCalledTimes(1));
    expect(api.updatePage).toHaveBeenCalledExactlyOnceWith('existing-pid', { name: 'Order template', isTemplate: true, templateCategory: 'CRM' });
    expect(props.onClose).toHaveBeenCalledTimes(1);
  });
  it('keeps an unsuccessful template save open and displays its API message', async () => {
    api.updatePage.mockResolvedValue({ code: '1', message: 'Save denied' });
    const props = save(); fireEvent.click(screen.getByTestId('template-save-btn'));
    expect(await screen.findByText('Save denied')).toBeInTheDocument();
    expect(props.onClose).not.toHaveBeenCalled(); expect(props.onSuccess).not.toHaveBeenCalled();
  });
  it('prevents overlay close while template save is pending', async () => {
    let resolve!: (result: { code: string }) => void;
    api.updatePage.mockReturnValue(new Promise(done => { resolve = done; }));
    const props = save(); fireEvent.click(screen.getByTestId('template-save-btn'));
    await waitFor(() => expect(screen.getByTestId('template-save-btn')).toBeDisabled());
    fireEvent.click(screen.getByTestId('save-as-template-dialog').parentElement!);
    expect(props.onClose).not.toHaveBeenCalled();
    resolve({ code: '0' }); await waitFor(() => expect(props.onSuccess).toHaveBeenCalledTimes(1));
    expect(props.onClose).toHaveBeenCalledTimes(1);
  });
});
