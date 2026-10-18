import type { paths } from '~/generated/internal-api/schema';
import type { FetchOptions, Result } from './types';
import { fetchResult } from './HttpClient';

export type JsonValue =
  | string
  | number
  | boolean
  | null
  | JsonValue[]
  | { [key: string]: JsonValue };
type JsonBody<T> = unknown extends T
  ? JsonValue
  : T extends readonly (infer Item)[]
    ? JsonBody<Item>[]
    : T extends object
      ? { [K in keyof T]: JsonBody<T[K]> }
      : T;

type Method = NonNullable<FetchOptions['method']>;
export type InternalPath<M extends Method> = {
  [P in keyof paths]: M extends keyof paths[P]
    ? NonNullable<paths[P][M]> extends never
      ? never
      : 'data' extends keyof SuccessBody<NonNullable<paths[P][M]>>
        ? P
        : never
    : never;
}[keyof paths];
type Operation<M extends Method, P extends InternalPath<M>> = M extends keyof paths[P]
  ? NonNullable<paths[P][M]>
  : never;
type Parameters<O, K extends string> = O extends { parameters: infer P }
  ? K extends keyof P
    ? NonNullable<P[K]>
    : never
  : never;
type MediaValue<T> = T extends { content: infer C } ? C[keyof C] : never;
type JsonMediaValue<T> = T extends { content: infer C }
  ? { [K in keyof C]: K extends 'application/json' | `${string}+json` ? C[K] : never }[keyof C]
  : never;
type Body<O> = O extends { requestBody: infer B }
  ? JsonMediaValue<B>
  : O extends { requestBody?: infer B }
    ? JsonMediaValue<NonNullable<B>>
    : never;
type SuccessBody<O> = O extends { responses: infer R }
  ? {
      [S in keyof R]: `${S & (string | number)}` extends `2${string}` ? MediaValue<R[S]> : never;
    }[keyof R]
  : never;
type Data<O> = SuccessBody<O> extends { data?: infer D } ? NonNullable<D> : never;
type Slot<K extends string, T> = [T] extends [never]
  ? { [P in K]?: never }
  : {} extends T
    ? { [P in K]?: T }
    : { [P in K]: T };
type BodySlot<O> = O extends { requestBody: unknown }
  ? { body: JsonBody<Body<O>> }
  : { body?: JsonBody<Body<O>> };

export type InternalRequestInput<M extends Method, P extends InternalPath<M>> = Slot<
  'path',
  Parameters<Operation<M, P>, 'path'>
> &
  Slot<'query', Parameters<Operation<M, P>, 'query'>> &
  Slot<'headers', Parameters<Operation<M, P>, 'header'>> &
  BodySlot<Operation<M, P>> & {
    options?: Omit<FetchOptions, 'method' | 'params' | 'body' | 'headers'>;
    request?: Request;
  };

export type InternalResponseData<M extends Method, P extends InternalPath<M>> = Data<
  Operation<M, P>
>;

interface TransportInput {
  path?: Record<string, unknown>;
  query?: Record<string, unknown>;
  headers?: Record<string, unknown>;
  body?: unknown;
  options?: Omit<FetchOptions, 'method' | 'params' | 'body' | 'headers'>;
  request?: Request;
}

/** OpenAPI form/explode query encoding; JSON filter expressions are declared strings. */
export function serializeInternalQuery(query: Record<string, unknown>): string {
  const result = new URLSearchParams();
  const append = (name: string, value: unknown) => {
    if (value === undefined || value === null) return;
    if (typeof value === 'object')
      throw new Error(`Nested query value requires an explicit serialization contract: ${name}`);
    result.append(name, String(value));
  };
  for (const [name, value] of Object.entries(query)) {
    if (Array.isArray(value)) value.forEach((item) => append(name, item));
    else if (value && typeof value === 'object')
      Object.entries(value).forEach(([key, item]) => append(key, item));
    else append(name, value);
  }
  return result.toString();
}

/** JSON-envelope operations reuse the existing session, BFF, error and cancellation stack. */
export function internalRequest<M extends Method, P extends InternalPath<M>>(
  method: M,
  path: P,
  ...args: {} extends InternalRequestInput<M, P>
    ? [input?: InternalRequestInput<M, P>]
    : [input: InternalRequestInput<M, P>]
): Promise<Result<InternalResponseData<M, P>>>;
export async function internalRequest(
  method: Method,
  path: string,
  input: TransportInput = {},
): Promise<Result<unknown>> {
  const remaining = new Set(Object.keys(input.path ?? {}));
  let url = path.replace(/\{([^}]+)\}/g, (_match, name: string) => {
    const value = input.path?.[name];
    if (value === undefined || value === null)
      throw new Error(`Missing internal API path parameter: ${name}`);
    remaining.delete(name);
    return encodeURIComponent(String(value));
  });
  if (remaining.size > 0)
    throw new Error(`Unexpected internal API path parameters: ${[...remaining].join(', ')}`);
  const query = serializeInternalQuery(input.query ?? {});
  if (query) url += `${url.includes('?') ? '&' : '?'}${query}`;
  const headers = Object.fromEntries(
    Object.entries(input.headers ?? {})
      .filter(([, value]) => value != null)
      .map(([name, value]) => [name, String(value)]),
  );
  return fetchResult(url, { ...input.options, method, headers, body: input.body }, input.request);
}
