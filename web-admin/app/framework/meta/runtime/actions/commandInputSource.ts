import type {
  CommandInputFieldConfig,
  CommandInputSourceConfig,
} from '~/framework/meta/schemas/types';
import { ActionCancelledError } from './ActionCancelledError';
import { currentFormDialogScope } from './formDialogLifecycle';
import { resolveCommandErrorMessage } from '~/framework/meta/utils/commandResponseErrors';

type Fetch = (endpoint: string, options?: any) => Promise<any>;
type Feedback = {
  token?: string;
  locale?: string;
  t?: (key: string) => string;
  staticFields?: unknown;
};
const forbidden = new Set(['__proto__', 'prototype', 'constructor']);
const own = (value: object, key: string) => Object.prototype.hasOwnProperty.call(value, key);
const object = (value: unknown): value is Record<string, unknown> =>
  Boolean(value) && typeof value === 'object' && !Array.isArray(value);

/** Preserve native handles while freezing plain invocation data, including cyclic contexts. */
export function snapshotCommandInputValue<T>(value: T, seen = new WeakMap<object, any>()): T {
  if (!value || typeof value !== 'object') return value;
  if (
    !Array.isArray(value) &&
    Object.getPrototypeOf(value) !== Object.prototype &&
    Object.getPrototypeOf(value) !== null
  )
    return value;
  if (seen.has(value)) return seen.get(value);
  const copy: any = Array.isArray(value) ? [] : {};
  seen.set(value, copy);
  for (const [key, item] of Object.entries(value))
    Object.defineProperty(copy, key, {
      value: snapshotCommandInputValue(item, seen),
      enumerable: true,
      writable: true,
      configurable: true,
    });
  return copy;
}
function safePath(path: unknown): path is string {
  return (
    typeof path === 'string' &&
    /^[\w$]+(?:\.[\w$]+)*$/.test(path) &&
    !path.split('.').some((key) => forbidden.has(key))
  );
}
function read(value: unknown, path: string): unknown {
  for (const key of path.split('.')) {
    if (!object(value) || !own(value, key)) return undefined;
    value = value[key];
  }
  return value;
}
function failureMessage(feedback: Feedback): string {
  const key = 'error.data_load_failed',
    translated = feedback.t?.(key);
  return translated && translated !== key
    ? translated
    : feedback.locale?.toLowerCase().startsWith('zh')
      ? '无法准备操作表单，请重试。'
      : 'Unable to prepare the form. Try again.';
}
export function validateCommandInputSource(source: unknown): source is CommandInputSourceConfig {
  if (
    !object(source) ||
    Object.keys(source).some((key) => !['endpoint', 'payloadBindings'].includes(key)) ||
    typeof source.endpoint !== 'string'
  )
    return false;
  const endpoint = source.endpoint;
  if (
    !endpoint.startsWith('/api/') ||
    /[\\\u0000-\u0020#]/.test(endpoint) ||
    endpoint.includes('${')
  )
    return false;
  const url = new URL(endpoint, 'https://aura.invalid');
  if (url.origin !== 'https://aura.invalid' || !url.pathname.startsWith('/api/')) return false;
  if (
    source.payloadBindings !== undefined &&
    (!object(source.payloadBindings) ||
      Object.entries(source.payloadBindings).some(
        ([key, path]) => !/^[A-Za-z_$][\w$]*$/.test(key) || forbidden.has(key) || !safePath(path),
      ))
  )
    return false;
  return true;
}

/** This read has no command, upload, authority or request-key side effects. */
export async function loadCommandInputSource(
  source: unknown,
  originalPayload: Record<string, unknown>,
  fetchResult: Fetch,
  feedback: Feedback = {},
): Promise<{
  fields: CommandInputFieldConfig[];
  payload: Record<string, unknown>;
  signal?: AbortSignal;
}> {
  const fallback = failureMessage(feedback);
  if (feedback.staticFields !== undefined || !validateCommandInputSource(source))
    throw new Error(fallback);
  const definition = snapshotCommandInputValue(source),
    payload = snapshotCommandInputValue(originalPayload),
    signal = currentFormDialogScope();
  if (signal?.aborted) throw new ActionCancelledError();
  let detach = () => {},
    result: any;
  try {
    const request = fetchResult(definition.endpoint, {
      method: 'get',
      token: feedback.token,
      signal,
    });
    result = signal
      ? await Promise.race([
          request,
          new Promise<never>((_resolve, reject) => {
            const abort = () => reject(new ActionCancelledError());
            signal.addEventListener('abort', abort, { once: true });
            detach = () => signal.removeEventListener('abort', abort);
            if (signal.aborted) abort();
          }),
        ])
      : await request;
  } catch (error) {
    if (signal?.aborted || error instanceof ActionCancelledError) throw new ActionCancelledError();
    throw new Error(fallback);
  } finally {
    detach();
  }
  if (signal?.aborted) throw new ActionCancelledError();
  let data: unknown = result;
  let resolved = false;
  for (let depth = 0; depth < 4; depth++) {
    if (
      !object(data) ||
      Number(data.httpStatus) >= 400 ||
      data.success === false ||
      data.applied === false ||
      (own(data, 'code') && String(data.code) !== '0')
    ) {
      const detail = object(data) && object(data.context) ? data.context.detail : undefined;
      throw new Error(
        detail
          ? resolveCommandErrorMessage(
              { context: { detail } },
              '',
              feedback.t,
              feedback.locale,
              fallback,
            )
          : fallback,
      );
    }
    if (own(data, 'inputFields')) {
      resolved = true;
      break;
    }
    if (!own(data, 'data')) throw new Error(fallback);
    data = data.data;
  }
  if (
    !resolved ||
    !object(data) ||
    !Array.isArray(data.inputFields) ||
    data.inputFields.length === 0 ||
    data.inputFields.length > 512
  )
    throw new Error(fallback);
  const names = new Set<string>();
  for (const field of data.inputFields) {
    if (
      !object(field) ||
      typeof field.field !== 'string' ||
      !field.field.trim() ||
      field.field !== field.field.trim() ||
      field.field.length > 256 ||
      field.field.split('.').some((key) => forbidden.has(key)) ||
      names.has(field.field)
    )
      throw new Error(fallback);
    names.add(field.field);
  }
  for (const [key, path] of Object.entries(definition.payloadBindings ?? {})) {
    const value = read(data, path);
    if (
      value === undefined ||
      (value !== null && !['string', 'number', 'boolean'].includes(typeof value)) ||
      (typeof value === 'number' && !Number.isFinite(value))
    )
      throw new Error(fallback);
    Object.defineProperty(payload, key, {
      value,
      enumerable: true,
      writable: true,
      configurable: true,
    });
  }
  return {
    fields: snapshotCommandInputValue(data.inputFields as CommandInputFieldConfig[]),
    payload,
    signal,
  };
}
