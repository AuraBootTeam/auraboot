import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthAppearanceEditor } from '../AuthAppearanceEditor';
import { COMMUNITY_BRANDING } from '~/config/branding';

const calls = vi.hoisted(() => ({ get: vi.fn(), put: vi.fn(), post: vi.fn() }));
vi.mock('~/shared/services/http-client', () => calls);
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: 'en-US', t: (_key: string, _params: unknown, fallback: string) => fallback }) }));
vi.mock('~/contexts/ThemeContext', () => ({ useTheme: () => ({ isDark: false }) }));
vi.mock('~/root-data', () => ({ useRootLoaderData: () => ({ branding: COMMUNITY_BRANDING }) }));
const appearance = { version: 1, template: 'split', defaultLocale: 'zh-CN' };
const view = { version: 4, publishedVersion: 2, draft: appearance, published: appearance, history: [{ version: 2, action: 'publish', actorId: 1, createdAt: '2026-10-02T01:00:00Z' }] };
const ok = (data: unknown) => ({ code: '0', message: 'OK', data });
async function open() { render(<AuthAppearanceEditor />); await screen.findByTestId('auth-appearance-editor'); }
beforeEach(() => { vi.clearAllMocks(); calls.get.mockResolvedValue(ok(view)); });
describe('deployment appearance editor', () => {
  it('shows access failure and does not render mutation controls', async () => {
    calls.get.mockResolvedValue({ code: '403', message: 'Platform administrator required' });
    render(<AuthAppearanceEditor />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Platform administrator required');
    expect(screen.queryByRole('button', { name: 'Save draft' })).not.toBeInTheDocument();
  });
  it('edits hidden copy locally then saves the expected-version draft', async () => {
    await open();
    fireEvent.change(screen.getByRole('combobox', { name: 'Headline display mode' }), { target: { value: 'hidden' } });
    expect(calls.put).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Publish' })).toBeDisabled();
    calls.put.mockResolvedValue(ok({ ...view, version: 5, draft: { ...appearance, content: { headline: { mode: 'hidden' } } } }));
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    await waitFor(() => expect(calls.put).toHaveBeenCalledWith('/api/admin/auth-appearance/draft', { expectedVersion: 4, appearance: { ...appearance, content: { headline: { mode: 'hidden' } } } }));
    expect(await screen.findByText('Draft saved. The live appearance has not changed.')).toBeVisible();
  });
  it('rejects a background template without a background image before writing', async () => {
    await open();
    fireEvent.change(screen.getByRole('combobox', { name: 'Layout template' }), { target: { value: 'background' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Configuration is incomplete');
    expect(calls.put).not.toHaveBeenCalled();
  });
  it('requires publish confirmation and preserves the exact expected version', async () => {
    await open();
    calls.post.mockResolvedValue(ok({ ...view, version: 5, publishedVersion: 5 }));
    fireEvent.click(screen.getByRole('button', { name: 'Publish' }));
    expect(screen.getByRole('alertdialog')).toBeVisible();
    expect(calls.post).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(calls.post).toHaveBeenCalledWith('/api/admin/auth-appearance/publish', { expectedVersion: 4 }));
  });
  it('requests a rollback of the selected release after confirmation', async () => {
    await open();
    calls.post.mockResolvedValue(ok({ ...view, version: 5, publishedVersion: 5 }));
    fireEvent.change(screen.getByRole('combobox', { name: 'Rollback target' }), { target: { value: '2' } });
    fireEvent.click(screen.getByRole('button', { name: 'Roll back' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(calls.post).toHaveBeenCalledWith('/api/admin/auth-appearance/rollback', { expectedVersion: 4, targetVersion: 2 }));
  });
  it('retains unsaved content when the server rejects a concurrent write', async () => {
    await open();
    fireEvent.change(screen.getByRole('combobox', { name: 'Headline display mode' }), { target: { value: 'hidden' } });
    calls.put.mockResolvedValue({ code: '409', message: 'Appearance changed; reload before saving' });
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Appearance changed');
    expect(screen.getByRole('combobox', { name: 'Headline display mode' })).toHaveValue('hidden');
    expect(screen.getByRole('button', { name: 'Save draft' })).toBeEnabled();
  });
  it('confirms discarding unsaved changes before reloading', async () => {
    await open();
    fireEvent.change(screen.getByRole('combobox', { name: 'Headline display mode' }), { target: { value: 'hidden' } });
    fireEvent.click(screen.getByRole('button', { name: 'Reload' }));
    expect(calls.get).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('alertdialog')).toHaveTextContent('Reloading discards unsaved changes');
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.getByRole('combobox', { name: 'Headline display mode' })).toHaveValue('hidden');
    fireEvent.click(screen.getByRole('button', { name: 'Reload' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(calls.get).toHaveBeenCalledTimes(2));
    expect(await screen.findByRole('combobox', { name: 'Headline display mode' })).toHaveValue('default');
  });
  it('uploads multipart content and saves only the returned public asset URL', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ok({ url: '/api/auth/appearance/assets/image.png' }) });
    vi.stubGlobal('fetch', fetchMock);
    try {
      await open();
      fireEvent.change(screen.getByLabelText('logoUrl Upload image'), { target: { files: [new File(['png'], 'logo.png', { type: 'image/png' })] } });
      await waitFor(() => expect(screen.getByRole('button', { name: 'Save draft' })).toBeEnabled());
      expect(fetchMock).toHaveBeenCalledWith('/api/admin/auth-appearance/assets', expect.objectContaining({ method: 'POST', body: expect.any(FormData) }));
      calls.put.mockResolvedValue(ok(view));
      fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
      await waitFor(() => expect(calls.put).toHaveBeenCalledWith('/api/admin/auth-appearance/draft', { expectedVersion: 4, appearance: { ...appearance, images: { mode: 'none', logoUrl: '/api/auth/appearance/assets/image.png' } } }));
    } finally { vi.unstubAllGlobals(); }
  });

  it('lets a new deployment save the initial default draft without a gratuitous edit', async () => {
    calls.get.mockResolvedValue(ok({ version: 0, publishedVersion: 0, draft: null, published: null, history: [] }));
    calls.put.mockResolvedValue(ok({ ...view, version: 1, publishedVersion: 0 }));
    await open();
    expect(screen.getByText(/Draft has not been saved/)).toBeVisible();
    expect(screen.getByRole('button', { name: 'Save draft' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Publish' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    await waitFor(() => expect(calls.put).toHaveBeenCalledWith('/api/admin/auth-appearance/draft', { expectedVersion: 0, appearance }));
  });

});
