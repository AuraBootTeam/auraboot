import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { actionRegistry } from '../ActionRegistry';
import { executeSimpleWorkbenchAction } from '~/framework/meta/rendering/blocks/workbenchBlockUtils';
import { registerFormDialogScope } from '../formDialogLifecycle';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
const io = vi.hoisted(() => ({ fetch: vi.fn() }));
vi.mock('~/shared/services/http-client', () => ({
  fetchResult: (...args: unknown[]) => io.fetch(...args),
}));
const fields = [
  {
    field: 'value_color',
    type: 'select',
    required: true,
    dataSource: { type: 'static', data: [{ value: 'blue', label: 'Blue' }] },
  },
];
const source = {
  endpoint: '/api/forms/product-one',
  payloadBindings: { expectedRevision: 'revision' },
};
const metadata = () => ({ code: '0', data: { inputFields: fields, revision: 'server-revision' } });
const t = (key: string) => key;
const context = () => ({
  locale: 'en',
  t,
  state: { name: 'Product one' },
  record: {},
  form: {},
  global: {},
});
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
const listeners: EventListener[] = [];
function prompt(submit = true) {
  const observed: any[] = [];
  const listener = ((event: CustomEvent) => {
    observed.push(event.detail);
    if (submit) event.detail.onSubmit({ value_color: 'blue' });
    else event.detail.onCancel();
  }) as EventListener;
  listeners.push(listener);
  window.addEventListener('dialog:form', listener);
  return observed;
}
afterEach(() => {
  for (const listener of listeners.splice(0)) window.removeEventListener('dialog:form', listener);
});
beforeEach(() => {
  io.fetch.mockReset();
  io.fetch.mockImplementation(async (url: string) =>
    url.startsWith('/api/forms/') ? metadata() : { code: '0', data: { saved: true } },
  );
});
function invoke(driver: string, args: any, state = context()) {
  if (driver === 'registry')
    return actionRegistry.execute('command.execute', {
      args,
      fetchResult: io.fetch,
      expressionContext: state,
      locale: 'en',
      t,
    });
  const runtime = {
    getContext: () => state,
    getEvaluator: () => ({
      evaluateObject: (x: unknown) => x,
      evaluateTemplate: (x: unknown) => x,
    }),
    getDataSourceManager: () => ({ reload: vi.fn() }),
  } as unknown as SchemaRuntime;
  return executeSimpleWorkbenchAction(runtime, { action: 'command.execute', args });
}
for (const driver of ['registry', 'workbench'])
  describe(driver + ' command input source', () => {
    it('preserves literal business defaults instead of evaluating metadata as expressions', async () => {
      io.fetch.mockImplementationOnce(async () => ({
        code: '0',
        data: {
          inputFields: [{ field: 'note', type: 'text', defaultValue: '${state.name}' }],
          revision: 'r',
        },
      }));
      const observed = prompt();
      await invoke(driver, { command: 'catalog:create_variant', inputFieldsSource: source });
      expect(observed[0].defaults.note).toBe('${state.name}');
    });
    it('binds explicit nested zero and false values without treating them as missing', async () => {
      io.fetch.mockImplementationOnce(async () => ({
        code: '0',
        data: { inputFields: fields, snapshot: { revision: 0 }, enabled: false },
      }));
      prompt();
      await invoke(driver, {
        command: 'catalog:create_variant',
        inputFieldsSource: {
          endpoint: source.endpoint,
          payloadBindings: { rowVersion: 'snapshot.revision', enabled: 'enabled' },
        },
      });
      expect(io.fetch.mock.calls[1][1].params.payload).toMatchObject({
        rowVersion: 0,
        enabled: false,
      });
    });
    it('rejects an envelope beyond the admitted depth instead of accepting unchecked inner fields', async () => {
      const value = {
        code: '0',
        data: {
          code: '0',
          data: {
            code: '0',
            data: { code: '0', data: { code: '403', inputFields: fields, revision: 'r' } },
          },
        },
      };
      io.fetch.mockResolvedValue(value);
      const observed = prompt();
      await expect(
        invoke(driver, { command: 'catalog:create_variant', inputFieldsSource: source }),
      ).rejects.toThrow();
      expect(observed).toHaveLength(0);
      expect(io.fetch.mock.calls.map((call) => call[0])).toEqual([source.endpoint]);
    });
    it('reads frozen fields and binds the server revision before collecting and writing once', async () => {
      const observed = prompt();
      await invoke(driver, {
        command: 'catalog:create_variant',
        payload: { productPid: 'product-one' },
        inputFieldsSource: source,
        inputFieldsPayloadKey: 'formInputs',
      });
      expect(io.fetch.mock.calls.map((call) => call[0])).toEqual([
        '/api/forms/product-one',
        '/api/meta/commands/execute/catalog:create_variant',
      ]);
      expect(observed).toHaveLength(1);
      expect(observed[0].fields).toEqual(fields);
      expect(io.fetch.mock.calls[1][1].params.payload).toEqual({
        productPid: 'product-one',
        expectedRevision: 'server-revision',
        formInputs: { value_color: 'blue' },
      });
    });
    it('cancels the form without dispatching a command', async () => {
      prompt(false);
      await invoke(driver, { command: 'catalog:create_variant', inputFieldsSource: source }).catch(
        (error) => {
          expect(error.name).toBe('ActionCancelledError');
        },
      );
      expect(io.fetch.mock.calls.map((call) => call[0])).toEqual(['/api/forms/product-one']);
    });
    it('keeps the clicked payload and title while the metadata request is pending', async () => {
      const pending = deferred<unknown>(),
        state = context(),
        args: any = {
          command: 'catalog:create_variant',
          payload: { productPid: 'product-one' },
          inputFieldsSource: source,
          inputFieldsTitle: 'Add ${state.name}',
        };
      io.fetch.mockImplementationOnce(() => pending.promise);
      const observed = prompt();
      const task = invoke(driver, args, state);
      args.payload.productPid = 'product-two';
      state.state.name = 'Product two';
      pending.resolve(metadata());
      await task;
      expect(observed[0].title).toBe('Add Product one');
      expect(io.fetch.mock.calls[1][1].params.payload.productPid).toBe('product-one');
    });
    it('does not open a late prompt or write after its original page scope is aborted', async () => {
      const scope = new AbortController(),
        unregister = registerFormDialogScope(scope.signal),
        pending = deferred<unknown>();
      io.fetch.mockImplementationOnce(() => pending.promise);
      const observed = prompt();
      try {
        const task = invoke(driver, {
          command: 'catalog:create_variant',
          inputFieldsSource: source,
        });
        scope.abort();
        pending.resolve(metadata());
        await task.catch((error) => {
          expect(error.name).toBe('ActionCancelledError');
        });
        expect(observed).toHaveLength(0);
        expect(io.fetch.mock.calls.map((call) => call[0])).toEqual(['/api/forms/product-one']);
      } finally {
        unregister();
      }
    });
    for (const invalid of [
      { endpoint: 'https://foreign.invalid/forms' },
      { endpoint: '//foreign.invalid/api/forms' },
      { endpoint: '/api/../outside' },
      { endpoint: '/api/forms', unknown: true },
      { endpoint: '/api/forms', payloadBindings: { constructor: 'revision' } },
      { endpoint: '/api/forms', payloadBindings: { expectedRevision: '__proto__.revision' } },
    ])
      it(
        'rejects unsafe source configuration before any network call: ' + JSON.stringify(invalid),
        async () => {
          prompt();
          await expect(
            invoke(driver, { command: 'catalog:create_variant', inputFieldsSource: invalid }),
          ).rejects.toThrow();
          expect(io.fetch).not.toHaveBeenCalled();
        },
      );
    it('rejects ambiguous static and remote fields before any request', async () => {
      prompt();
      await expect(
        invoke(driver, {
          command: 'catalog:create_variant',
          inputFields: fields,
          inputFieldsSource: source,
        }),
      ).rejects.toThrow();
      expect(io.fetch).not.toHaveBeenCalled();
    });
    for (const value of [
      { code: '403', message: 'Forbidden' },
      { code: '0', data: { inputFields: [], revision: 'x' } },
      { code: '0', data: { inputFields: fields } },
      { code: '0', data: { inputFields: [{ field: '__proto__' }], revision: 'x' } },
      { code: '0', data: { inputFields: [fields[0], fields[0]], revision: 'x' } },
      { code: '0', data: { code: '403', inputFields: fields, revision: 'x' } },
    ])
      it(
        'refuses failed or invalid metadata with no dialog and no write: ' + JSON.stringify(value),
        async () => {
          io.fetch.mockResolvedValue(value);
          const observed = prompt();
          await expect(
            invoke(driver, { command: 'catalog:create_variant', inputFieldsSource: source }),
          ).rejects.toThrow();
          expect(observed).toHaveLength(0);
          expect(io.fetch.mock.calls.map((call) => call[0])).toEqual(['/api/forms/product-one']);
        },
      );
    it('does not expose thrown transport diagnostics as business copy', async () => {
      io.fetch.mockRejectedValue(new Error('private transport diagnostic'));
      let observed: any;
      try {
        await invoke(driver, { command: 'catalog:create_variant', inputFieldsSource: source });
      } catch (error) {
        observed = error;
      }
      expect(observed).toBeInstanceOf(Error);
      expect(observed.message).not.toContain('private transport');
      expect(io.fetch.mock.calls.map((call) => call[0])).toEqual(['/api/forms/product-one']);
    });
  });
