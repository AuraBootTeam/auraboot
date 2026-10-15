import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { OpenApiActionPublicationEditor } from '../OpenApiActionPublicationEditor';
import { OpenApiInstallationGrantEditor } from '../OpenApiInstallationGrantEditor';
import type { ActionPublicationView, ActionSource, InstallationActionPolicy, PublicAction } from '~/services/open-api-publications';

vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: 'en-US', t: (_key: string, _params: unknown, fallback: string) => fallback }) }));
const ok = (data: unknown) => Promise.resolve({ ok: true, status: 200, json: async () => ({ code: '0', data }) });
const source: ActionSource = { sourceKind: 'handler', sourceCode: 'core.visible-page', name: 'Visible page summary', fingerprint: 'a'.repeat(64), permission: null,
  parameters: { limit: { type: 'integer', required: true, nullable: false, items: null } }, inputFields: {}, effectFields: [],
  outputFields: { countOnPage: 'integer', hasMore: 'boolean' }, minTimeoutMs: 5000, available: true, reason: null };
afterEach(() => vi.unstubAllGlobals());

describe('Public business API management blocks', () => {
  it('saves a typed registered implementation and requires explicit publish confirmation', async () => {
    const user = userEvent.setup(); let stored: ActionPublicationView | undefined;
    vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
      if (url === '/api/open-platform/action-publications') return ok(stored ? [stored] : []);
      if (url === '/api/open-platform/publications') return ok([{ code: 'office.devices', status: 'published', activeVersion: 1 }]);
      if (url.startsWith('/api/open-platform/action-sources?')) return ok([source]);
      if (url.endsWith('/draft') && init?.method === 'PUT') {
        const payload = JSON.parse(String(init.body)); stored = { code: payload.definition.code, draft: payload.definition as PublicAction,
          draftRevision: 1, activeVersion: null, status: 'draft' }; return ok(stored);
      }
      if (url.endsWith('/publish') && stored) { stored = { ...stored, status: 'published', activeVersion: 1 }; return ok(stored); }
      throw new Error('Unexpected management request');
    }));
    render(<OpenApiActionPublicationEditor />, { wrapper: MemoryRouter });
    await user.click(await screen.findByRole('button', { name: 'New business API' }));
    await user.type(screen.getByLabelText('Public API address'), 'office.devices.summary');
    await user.selectOptions(screen.getByLabelText('Bound public resource'), 'office.devices');
    await screen.findByRole('option', { name: 'Visible page summary · Read-only handler' });
    await user.selectOptions(screen.getByLabelText('Registered implementation'), 'handler:core.visible-page');
    await user.type(screen.getByLabelText('Execution scope'), 'office.devices.summary.execute');
    await user.click(screen.getByRole('button', { name: 'Save and validate business draft' }));
    await screen.findByText('Business API draft saved and source validated.');
    const write = vi.mocked(fetch).mock.calls.find(([url]) => String(url).endsWith('/draft'))!;
    const payload = JSON.parse(String(write[1]?.body));
    expect(payload.expectedRevision).toBe(0);
    expect(payload.definition).toMatchObject({ code: 'office.devices.summary', sourceKind: 'handler', resourceCode: 'office.devices', sourceCode: 'core.visible-page',
      sourceFingerprint: source.fingerprint, parameters: source.parameters, outputFields: source.outputFields });
    expect(screen.getByTestId('action-publication-editor')).not.toHaveTextContent(source.fingerprint);
    await user.click(screen.getByRole('button', { name: 'Publish business API' }));
    expect(fetch).not.toHaveBeenCalledWith(expect.stringContaining('/publish'), expect.anything());
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    await user.click(screen.getByRole('button', { name: 'Publish business API' }));
    await user.click(screen.getByRole('button', { name: 'Confirm' }));
    await screen.findByText('Business API published. Installations still require explicit authority.');
    expect(fetch).toHaveBeenCalledWith('/api/open-platform/action-publications/office.devices.summary/publish', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ expectedRevision: 1 }),
    }));
    expect(screen.getByRole('button', { name: 'Download business OpenAPI' })).toBeEnabled();
  });

  it('disables unavailable source implementations while keeping supported handlers selectable', async () => {
    const user = userEvent.setup();
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url === '/api/open-platform/action-publications') return ok([]);
      if (url === '/api/open-platform/publications') return ok([{ code: 'office.devices', status: 'published' }]);
      if (url.startsWith('/api/open-platform/action-sources?')) return ok([{ ...source, sourceKind: 'command', sourceCode: 'asset:unsafe', name: 'Unavailable command', available: false }, source]);
      throw new Error('Unexpected management request');
    }));
    render(<OpenApiActionPublicationEditor />, { wrapper: MemoryRouter });
    await user.click(await screen.findByRole('button', { name: 'New business API' }));
    await user.selectOptions(screen.getByLabelText('Bound public resource'), 'office.devices');
    expect(await screen.findByRole('option', { name: 'Unavailable command · Not eligible for publication' })).toBeDisabled();
    expect(screen.getByRole('option', { name: 'Visible page summary · Read-only handler' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Save and validate business draft' })).toBeDisabled();
  });

  it('fails closed on administrator permission denial', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve({ ok: false, status: 403, json: async () => ({ message: 'denied' }) })));
    render(<OpenApiActionPublicationEditor />, { wrapper: MemoryRouter });
    expect(await screen.findByRole('alert')).toHaveTextContent('Open Platform administrator permission');
    expect(screen.getByRole('button', { name: 'New business API' })).toBeDisabled();
  });

  it('saves and revokes explicit command effects through the existing installation authority editor', async () => {
    const user = userEvent.setup(); let policy: InstallationActionPolicy | null = null;
    vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
      if (url.endsWith('/resources')) return ok([{ code: 'office.devices', status: 'published', contract: { schemaVersion: 1 }, policy: { operations: ['update'] } }]);
      if (url.endsWith('/queries')) return ok([]);
      if (url.endsWith('/actions')) return ok([{ code: 'office.devices.assign', status: 'published', policy,
        contract: { resourceCode: 'office.devices', sourceKind: 'command', schemaVersion: 1, scopes: { execute: 'office.devices.assign.execute' }, effectFields: ['assignedTo', 'status'] } }]);
      if (url.endsWith('/actions/office.devices.assign') && init?.method === 'PUT') { policy = JSON.parse(String(init.body)); return ok(null); }
      if (url.endsWith('/actions/office.devices.assign') && init?.method === 'DELETE') { policy = null; return ok(null); }
      throw new Error('Unexpected installation request');
    }));
    render(<OpenApiInstallationGrantEditor installationPid="inst-1" active scopes={['office.devices.assign.execute', 'office.devices.write']} onClose={vi.fn()} />);
    await screen.findByRole('option', { name: 'office.devices.assign · v1' });
    await user.selectOptions(screen.getByLabelText('Authority contract'), 'action:office.devices.assign');
    await user.click(screen.getByLabelText('Allow this business API'));
    await user.click(screen.getByLabelText('assignedTo'));
    await user.click(screen.getByLabelText('status'));
    await user.click(screen.getByRole('button', { name: 'Save installation authority' }));
    await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/open-platform/installations/inst-1/actions/office.devices.assign',
      expect.objectContaining({ method: 'PUT', body: JSON.stringify({ execute: true, effectFields: ['assignedTo', 'status'] }) })));
    await user.click(screen.getByRole('button', { name: 'Revoke installation authority' }));
    await user.click(screen.getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Revoke installation authority' })).toBeDisabled());
    expect(screen.getByLabelText('Allow this business API')).not.toBeChecked();
  });
});
