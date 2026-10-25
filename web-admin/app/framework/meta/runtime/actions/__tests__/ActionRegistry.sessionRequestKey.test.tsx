import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { usePageRuntime } from '../../../rendering/pages/hooks/usePageRuntime';
import { FormButtonsBlockRenderer } from '../../../rendering/blocks/FormButtonsBlockRenderer';
import { createRequestKeySession } from '../request-key/session';
const auth = vi.hoisted(() => ({
  user: { id: 'u1', tenantId: '11' } as any,
  transport: vi.fn(),
  toast: vi.fn(),
}));
vi.mock('~/contexts/AuthContext', () => ({
  useAuth: () => ({
    user: auth.user,
    permissions: { permissionCodes: [] },
    token: 'fixture',
    hasPermission: () => true,
  }),
}));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: 'en', t: (s: string) => s }),
}));
vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({
    showSuccessToast: auth.toast,
    showErrorToast: auth.toast,
    showWarningToast: auth.toast,
    showInfoToast: auth.toast,
  }),
}));
vi.mock('~/shared/services/http-client', () => ({
  fetchResult: (...a: any[]) => auth.transport(...a),
}));
vi.mock('~/framework/extensions/use-contribution', () => ({
  useContributionRegistry: () => ({ listPageRuntimeHooks: () => [] }),
}));
vi.mock('~/framework/meta/hooks/useDataSync', () => ({ useDataSync: () => {} }));
const schema = {
  id: 'order',
  pageKey: 'example_order_detail',
  modelCode: 'example',
  state: { requestKey: null },
  blocks: [],
} as any;
let observed: any;
function Page({ record = 'p1', revision = 1, revisionScoped = false }: { record?: string; revision?: number; revisionScoped?: boolean }) {
  const { runtime } = usePageRuntime(schema, {
    additionalContext: {
      global: { user: { id: 'forged' }, tenant: { id: '999' } },
      form: { pid: record, quantity: '250.0000' },
      record: { pid: record, revision },
      $page: { recordPid: record },
    },
  });
  observed = runtime;
  return runtime ? (
    <FormButtonsBlockRenderer
      runtime={runtime}
      block={
        {
          id: 'actions',
          blockType: 'form-buttons',
          buttons: [
            {
              code: 'send',
              content: 'Send',
              action: {
                type: 'flow',
                steps: [
                  {
                    action: 'state.ensureRequestKey',
                    args: { field: revisionScoped ? 'requestKey_${record.revision}' : 'requestKey', persistence: 'session' },
                  },
                  {
                    action: 'api.request',
                    endpoint: '/api/example/ship',
                    method: 'post',
                    body: { requestKey: revisionScoped ? "${state['requestKey_' + record.revision]}" : '${state.requestKey}', quantity: '${form.quantity}' },
                  },
                  {
                    action: 'state.clearRequestKey',
                    args: { field: revisionScoped ? 'requestKey_${record.revision}' : 'requestKey', persistence: 'session' },
                  },
                ],
              },
            },
            { code: 'refresh', content: 'Refresh' },
            {
              code: 'check',
              content: 'Check attempt',
              action: {
                type: 'flow',
                steps: [
                  {
                    action: 'state.peekRequestKey',
                    args: { field: 'requestKey', persistence: 'session' },
                  },
                  {
                    type: 'if',
                    condition: '${state.requestKey != null}',
                    trueNext: 'query',
                    falseNext: 'absent',
                  },
                  {
                    id: 'query',
                    action: 'api.request',
                    endpoint: '/api/example/attempt',
                    method: 'post',
                    body: {
                      targetRecordPid: '${record.pid}',
                      payload: { requestKey: '${state.requestKey}' },
                    },
                    target: 'attemptResult',
                    next: 'end',
                  },
                  { id: 'absent', action: 'state.set', args: { noAttempt: true }, next: 'end' },
                  { id: 'end' },
                ],
              },
            },
          ],
        } as any
      }
    />
  ) : null;
}
function mount(record = 'p1') {
  return render(
    <MemoryRouter>
      <Page record={record} />
    </MemoryRouter>,
  );
}
async function send() {
  await waitFor(() => expect(screen.getByTestId('form-btn-send')).toBeDefined());
  fireEvent.click(screen.getByTestId('form-btn-send'));
}
function observeSessionStorage() {
  const actual = window.sessionStorage;
  const writes = vi.fn((key: string, value: string) => actual.setItem(key, value));
  const removes = vi.fn((key: string) => actual.removeItem(key));
  const reads = vi.fn((key: string) => actual.getItem(key));
  const delegate = {
    getItem: reads,
    setItem: writes,
    removeItem: removes,
    key: (index: number) => actual.key(index),
    clear: () => actual.clear(),
    get length() {
      return actual.length;
    },
  } as Storage;
  vi.spyOn(window, 'sessionStorage', 'get').mockReturnValue(delegate);
  return { writes, removes, reads };
}
const identity = { tenantId: '11', userId: 'u1', pageKey: 'example_order_detail', recordPid: 'p1' };
describe('Actual authenticated page runtime persists scoped UUID attempts', () => {
  beforeEach(() => {
    sessionStorage.clear();
    auth.user = { id: 'u1', tenantId: '11' };
    auth.toast.mockReset();
    auth.transport.mockReset().mockResolvedValue({ code: '500', message: 'Unknown outcome' });
  });
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });
  it('keeps an uncertain attempt stable within a revision and isolates a later explicit retry revision', async () => {
    const mountRevision = (revision: number) => render(<MemoryRouter><Page revision={revision} revisionScoped /></MemoryRouter>);
    const first = mountRevision(2);await send();
    await waitFor(() => expect(auth.transport).toHaveBeenCalledTimes(1));
    const original = auth.transport.mock.calls[0][1].params.requestKey;
    expect(original).toMatch(/^[a-f0-9-]{36}$/);
    first.unmount();const second = mountRevision(2);await send();
    await waitFor(() => expect(auth.transport).toHaveBeenCalledTimes(2));
    expect(auth.transport.mock.calls[1][1].params.requestKey).toBe(original);
    second.unmount();mountRevision(3);await send();
    await waitFor(() => expect(auth.transport).toHaveBeenCalledTimes(3));
    expect(auth.transport.mock.calls[2][1].params.requestKey).not.toBe(original);
  });
  it('peek restores an existing UUID across mounted recovery flows without generating, persisting or clearing it', async () => {
    const stored = createRequestKeySession(() => identity).ensure('requestKey', null);
    const random = vi.spyOn(globalThis.crypto, 'randomUUID');
    const { writes, removes, reads } = observeSessionStorage();
    auth.transport.mockResolvedValue({
      code: '0',
      data: { phaseReached: 'completed', data: { submitted: true } },
    });
    const first = mount();
    await waitFor(() => expect(screen.getByTestId('form-btn-check')).toBeDefined());
    fireEvent.click(screen.getByTestId('form-btn-check'));
    await waitFor(() => expect(auth.transport).toHaveBeenCalledTimes(1));
    expect(auth.transport.mock.calls[0]).toEqual([
      '/api/example/attempt',
      { method: 'post', params: { targetRecordPid: 'p1', payload: { requestKey: stored } } },
    ]);
    expect(observed.getContext().state.requestKey).toBe(stored);
    expect(observed.getContext().state.attemptResult).toEqual({
      phaseReached: 'completed',
      data: { submitted: true },
    });
    first.unmount();
    mount();
    await waitFor(() => expect(screen.getByTestId('form-btn-check')).toBeDefined());
    fireEvent.click(screen.getByTestId('form-btn-check'));
    await waitFor(() => expect(auth.transport).toHaveBeenCalledTimes(2));
    expect(auth.transport.mock.calls[1][1].params.payload.requestKey).toBe(stored);
    expect(random).not.toHaveBeenCalled();
    expect(writes).not.toHaveBeenCalled();
    expect(removes).not.toHaveBeenCalled();
    expect(reads).toHaveBeenCalled();
    expect(sessionStorage.getItem(sessionStorage.key(0)!)).toBe(stored);
  });
  it('peek absent or another authenticated namespace stays null and does not query or generate', async () => {
    createRequestKeySession(() => identity).ensure('requestKey', null);
    const random = vi.spyOn(globalThis.crypto, 'randomUUID');
    const { writes } = observeSessionStorage();
    for (const [tenantId, id, record] of [
      ['11', 'other', 'p1'],
      ['22', 'u1', 'p1'],
      ['11', 'u1', 'p2'],
    ]) {
      auth.user = { id, tenantId };
      const view = mount(record);
      await waitFor(() => expect(screen.getByTestId('form-btn-check')).toBeDefined());
      fireEvent.click(screen.getByTestId('form-btn-check'));
      await waitFor(() => expect(observed.getContext().state.noAttempt).toBe(true));
      expect(observed.getContext().state.requestKey).toBeNull();
      view.unmount();
    }
    expect(auth.transport).not.toHaveBeenCalled();
    expect(random).not.toHaveBeenCalled();
    expect(writes).not.toHaveBeenCalled();
    expect(sessionStorage.length).toBe(1);
  });
  it('peek refuses corrupt storage, missing persisted match, forged state or live identity changes atomically', async () => {
    const controller = createRequestKeySession(() => identity);
    const retained = controller.ensure('requestKey', null);
    const name = sessionStorage.key(0)!;
    sessionStorage.setItem(name, 'invalid');
    const view = mount();
    await waitFor(() => expect(screen.getByTestId('form-btn-check')).toBeDefined());
    fireEvent.click(screen.getByTestId('form-btn-check'));
    await waitFor(() => expect(auth.toast).toHaveBeenCalled());
    expect(auth.transport).not.toHaveBeenCalled();
    expect(sessionStorage.getItem(name)).toBe('invalid');
    sessionStorage.setItem(name, retained);
    observed
      .getStateManager()
      .updateState(observed.getScopeId(), 'requestKey', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
    auth.toast.mockClear();
    fireEvent.click(screen.getByTestId('form-btn-check'));
    await waitFor(() => expect(auth.toast).toHaveBeenCalled());
    expect(auth.transport).not.toHaveBeenCalled();
    expect(sessionStorage.getItem(name)).toBe(retained);
    sessionStorage.removeItem(name);
    expect(() => controller.peek('requestKey', retained)).toThrow('does not match');
    sessionStorage.setItem(name, retained);
    observed.getStateManager().updateState(observed.getScopeId(), 'requestKey', null);
    auth.toast.mockClear();
    auth.user = { id: 'u1', tenantId: '33' };
    view.rerender(
      <MemoryRouter>
        <Page />
      </MemoryRouter>,
    );
    fireEvent.click(screen.getByTestId('form-btn-check'));
    await waitFor(() => expect(auth.toast).toHaveBeenCalled());
    expect(auth.transport).not.toHaveBeenCalled();
    expect(sessionStorage.getItem(name)).toBe(retained);
  });
  it('retains unknown outcome across remount/refresh/reset; success clears both before a new attempt', async () => {
    const first = mount();
    await send();
    await waitFor(() => expect(auth.transport).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(auth.toast).toHaveBeenCalled());
    const key = auth.transport.mock.calls[0][1].params.requestKey;
    expect(key).toMatch(/^[a-f0-9-]{36}$/);
    expect(sessionStorage.length).toBe(1);
    expect(sessionStorage.getItem(sessionStorage.key(0)!)).toBe(key);
    expect(decodeURIComponent(sessionStorage.key(0)!)).toContain(
      '["11","u1","example_order_detail","p1","requestKey"]',
    );
    observed.getStateManager().updateState(observed.getScopeId(), 'requestKey', null);
    fireEvent.click(screen.getByTestId('form-btn-refresh'));
    await send();
    await waitFor(() => expect(auth.transport).toHaveBeenCalledTimes(2));
    expect(auth.transport.mock.calls[1][1].params.requestKey).toBe(key);
    first.unmount();
    const second = mount();
    await send();
    await waitFor(() => expect(auth.transport).toHaveBeenCalledTimes(3));
    expect(auth.transport.mock.calls[2][1].params.requestKey).toBe(key);
    auth.transport.mockResolvedValue({ code: '0', data: { ok: true } });
    await send();
    await waitFor(() => expect(sessionStorage.length).toBe(0));
    expect(observed.getContext().state.requestKey).toBeNull();
    await send();
    await waitFor(() => expect(auth.transport).toHaveBeenCalledTimes(5));
    expect(auth.transport.mock.calls[4][1].params.requestKey).not.toBe(key);
    second.unmount();
  });
  it('isolates tenant/user/page/record identities using actual auth props', async () => {
    const keys: string[] = [];
    for (const [tenant, user, record] of [
      ['11', 'u1', 'p1'],
      ['22', 'u1', 'p1'],
      ['11', 'u2', 'p1'],
      ['11', 'u1', 'p2'],
    ]) {
      auth.user = { id: user, tenantId: tenant };
      const view = mount(record);
      await send();
      await waitFor(() => expect(auth.transport).toHaveBeenCalledTimes(keys.length + 1));
      keys.push(auth.transport.mock.calls.at(-1)![1].params.requestKey);
      view.unmount();
    }
    expect(new Set(keys).size).toBe(4);
    expect(sessionStorage.length).toBe(4);
    const another = createRequestKeySession(() => ({ ...identity, pageKey: 'other_page' }));
    expect(another.ensure('requestKey', null)).not.toBe(keys[0]);
    expect(sessionStorage.length).toBe(5);
  });
  it('rejects corrupt storage/mismatched state/auth drift before POST without replacing old attempts', async () => {
    const value = createRequestKeySession(() => identity).ensure('requestKey', null);
    const name = sessionStorage.key(0)!;
    sessionStorage.setItem(name, 'broken');
    const view = mount();
    await send();
    await waitFor(() => expect(auth.toast).toHaveBeenCalled());
    expect(auth.transport).not.toHaveBeenCalled();
    expect(sessionStorage.getItem(name)).toBe('broken');
    sessionStorage.setItem(name, value);
    auth.toast.mockClear();
    observed
      .getStateManager()
      .updateState(observed.getScopeId(), 'requestKey', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
    await send();
    await waitFor(() => expect(auth.toast).toHaveBeenCalled());
    expect(auth.transport).not.toHaveBeenCalled();
    observed.getStateManager().updateState(observed.getScopeId(), 'requestKey', null);
    auth.toast.mockClear();
    auth.user = { id: 'other', tenantId: '11' };
    view.rerender(
      <MemoryRouter>
        <Page />
      </MemoryRouter>,
    );
    await send();
    await waitFor(() => expect(auth.toast).toHaveBeenCalled());
    expect(auth.transport).not.toHaveBeenCalled();
    expect(sessionStorage.getItem(name)).toBe(value);
  });
  it('rejects incomplete/unsafe auth and storage failures', () => {
    for (const bad of [
      { ...identity, tenantId: undefined },
      { ...identity, tenantId: 9007199254740992 },
      { ...identity, userId: 'current-user' },
      { ...identity, recordPid: '' },
      { ...identity, pageKey: undefined },
    ])
      expect(() => createRequestKeySession(() => bad).ensure('requestKey', null)).toThrow();
    let current: any = { ...identity };
    const guarded = createRequestKeySession(() => current);
    const retained = guarded.ensure('requestKey', null);
    for (const changed of [
      { ...identity, tenantId: '22' },
      { ...identity, userId: 'u2' },
      { ...identity, pageKey: 'other' },
      { ...identity, recordPid: 'p2' },
    ]) {
      current = changed;
      expect(() => guarded.ensure('requestKey', retained)).toThrow('identity changed');
      expect(() => guarded.clear('requestKey', retained)).toThrow('identity changed');
    }
    const storage = {
      getItem: () => null,
      setItem: () => {
        throw new Error('denied');
      },
      removeItem: vi.fn(),
    };
    expect(() =>
      createRequestKeySession(
        () => identity,
        () => storage,
      ).ensure('requestKey', null),
    ).toThrow('denied');
    expect(storage.removeItem).not.toHaveBeenCalled();
  });
});
