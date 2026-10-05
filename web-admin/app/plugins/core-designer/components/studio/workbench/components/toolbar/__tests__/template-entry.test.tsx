import React from 'react';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { parse } from 'yaml';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import { DesignerToolbar } from '../DesignerToolbar';
const state = vi.hoisted(() => ({ allowed: true, getTemplates: vi.fn(), createPage: vi.fn() }));
vi.mock('~/contexts/AuthContext', () => ({ usePermissions: () => ({ hasPermission: () => state.allowed }) }));
vi.mock('~/plugins/core-designer/components/studio/components/AiPageGenerateDialog', () => ({ AiPageGenerateDialog: () => null }));
vi.mock('~/plugins/core-designer/components/studio/services/page-manager/pageApi', () => ({
  getTemplates: state.getTemplates, createPage: state.createPage, updatePage: vi.fn(), getPageByPid: vi.fn(),
  getPageByPageKey: vi.fn(), getVersionHistory: vi.fn(), createVersion: vi.fn(), publishPage: vi.fn(),
  unpublishPage: vi.fn(), rollbackToVersion: vi.fn(), compareVersions: vi.fn(),
}));
const dictionary = (locale: string) => parse(readFileSync(path.resolve(process.cwd(), `../platform/src/main/resources/i18n.${locale}.yaml`), 'utf8'));
beforeEach(() => {
  localStorage.clear(); vi.clearAllMocks(); state.allowed = true;
  state.getTemplates.mockResolvedValue({ code: '0', data: [{ pid: 'order-template', name: 'Orders', title: 'Orders',
    kind: 'list', modelCode: 'orders', schemaVersion: 4, blocks: [{ id: 'orders', blockType: 'table' }], extension: { enableMultiView: true } }] });
  state.createPage.mockResolvedValue({ code: '0', data: { pid: 'created-page' } });
});
afterEach(() => cleanup());
describe.each(['en-US', 'zh-CN'])('toolbar template entry in %s', locale => {
  const mount = (props = {}) => render(<I18nProvider initialLocale={locale} initialData={dictionary(locale)}>
    <DesignerToolbar onPageCreated={vi.fn()} {...props} />
  </I18nProvider>);
  it('opens the real gallery, creates a v4 page and delivers its exact pid', async () => {
    const onPageCreated = vi.fn(); mount({ onPageCreated });
    fireEvent.click(screen.getByTestId('toolbar-create-from-template'));
    expect(screen.getByRole('dialog')).toHaveAccessibleName(dictionary(locale).designer_template.select);
    fireEvent.click(await screen.findByTestId('template-card-order-template'));
    fireEvent.change(screen.getByTestId('new-page-name-input'), { target: { value: 'New orders' } });
    fireEvent.change(screen.getByTestId('new-page-key-input'), { target: { value: 'new_orders' } });
    fireEvent.click(screen.getByTestId('create-from-template-btn'));
    await waitFor(() => expect(onPageCreated).toHaveBeenCalledExactlyOnceWith('created-page'));
    expect(state.createPage).toHaveBeenCalledWith(expect.objectContaining({ name: 'New orders', pageKey: 'new_orders',
      schemaVersion: 4, blocks: [{ id: 'orders', blockType: 'table' }], extension: { enableMultiView: true } }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
  it('denies both template entries without manage permission and performs no API call', () => {
    state.allowed = false; mount({ pageMeta: { id: 'source', title: 'Source' } });
    const create = screen.getByTestId('toolbar-create-from-template'); const save = screen.getByTestId('toolbar-save-as-template');
    expect(create).toBeDisabled(); expect(save).toBeDisabled(); fireEvent.click(create); fireEvent.click(save);
    expect(state.getTemplates).not.toHaveBeenCalled(); expect(state.createPage).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
  it('keeps API errors in the dialog without navigating', async () => {
    const onPageCreated = vi.fn(); state.createPage.mockResolvedValue({ code: 'ERROR', message: 'Permission denied' });
    mount({ onPageCreated }); fireEvent.click(screen.getByTestId('toolbar-create-from-template'));
    fireEvent.click(await screen.findByTestId('template-card-order-template')); fireEvent.click(screen.getByTestId('create-from-template-btn'));
    expect(await screen.findByText('Permission denied')).toBeVisible();
    expect(onPageCreated).not.toHaveBeenCalled(); expect(screen.getByRole('dialog')).toBeVisible();
  });
  it('returns to template selection after cancelling and reopening', async () => {
    mount(); fireEvent.click(screen.getByTestId('toolbar-create-from-template'));
    fireEvent.click(await screen.findByTestId('template-card-order-template'));
    expect(screen.getByTestId('new-page-name-input')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: dictionary(locale).designer_template.close }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('toolbar-create-from-template'));
    expect(await screen.findByTestId('template-card-order-template')).toBeVisible();
    expect(screen.queryByTestId('new-page-name-input')).not.toBeInTheDocument();
    expect(state.createPage).not.toHaveBeenCalled();
  });
});
