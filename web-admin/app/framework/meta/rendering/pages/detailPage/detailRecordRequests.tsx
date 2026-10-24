import { decodeRouteContextFromSearch } from '~/framework/meta/rendering/pages/hooks/usePageRuntime';
import { buildApiEndpoint } from '~/routes/_shared/dynamic-route-utils';
import { fetchResult } from '~/shared/services/http-client';
import { expressionEvaluator } from '~/framework/meta/runtime/expression/evaluator';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';
import { ResultHelper } from '~/utils/type';
import type { RecordData, DetailListResult, DetailRecordRefreshSnapshot } from './detailPageTypes';

export function isDetailRecordAccessDenied(result: {
  httpStatus?: unknown;
  code?: unknown;
}): boolean {
  return Number(result.httpStatus ?? result.code) === 403;
}

export function isDetailRecordNotFound(result: { httpStatus?: unknown; code?: unknown }): boolean {
  return Number(result.httpStatus ?? result.code) === 404;
}

export function applyRecordEndpointTemplate(template: string, recordPid: string): string {
  const encoded = encodeURIComponent(recordPid);
  const legacyRecordKey = 'record' + 'Id';
  return template
    .replace(/\{recordPid\}/g, encoded)
    .replace(/\{pid\}/g, encoded)
    .replace(new RegExp(`\\{${legacyRecordKey}\\}`, 'g'), encoded)
    .replace(/\{id\}/g, encoded);
}

export function buildDetailRecordEndpoint(
  tableName: string,
  recordPid: string,
  schema?: { dataSource?: Record<string, any> } | null,
): string {
  const dataSource = schema?.dataSource;
  if (dataSource?.type === 'api') {
    const template = dataSource.detailEndpoint || dataSource.recordEndpoint;
    if (typeof template === 'string' && template.trim()) {
      return applyRecordEndpointTemplate(template.trim(), recordPid);
    }
    if (typeof dataSource.endpoint === 'string' && dataSource.endpoint.trim()) {
      return `${dataSource.endpoint.replace(/\/+$/, '')}/${encodeURIComponent(recordPid)}`;
    }
  }
  return buildApiEndpoint(tableName, recordPid);
}

export function shouldSkipDetailModelFieldMeta(
  schema: { extension?: Record<string, any> } | null | undefined,
): boolean {
  const extension = schema?.extension ?? {};
  return extension.skipFieldMeta === true || extension.skipDynamicFieldMeta === true;
}

export function hasDetailApiDataSource(
  schema: { extension?: Record<string, any> } | null | undefined,
): boolean {
  const ds = schema?.extension?.dataSource as { type?: string; endpoint?: string } | undefined;
  return ds?.type === 'api' && typeof ds.endpoint === 'string' && ds.endpoint.trim().length > 0;
}

/**
 * Read a nested value from an object by dot-path (e.g. "version", "a.b.c").
 * Returns the object itself when path is empty; undefined when any segment is missing.
 */
export function getByDataPath(obj: any, path?: string): any {
  if (obj == null || !path) return obj;
  return path.split('.').reduce((acc, key) => (acc == null ? undefined : acc[key]), obj);
}

/**
 * Resolve the master-record endpoint + method for a detail page.
 *
 * When the page declares a page-level `extension.dataSource` of `type: "api"`, the master
 * record is fetched from that custom endpoint (e.g. an external REST controller like
 * `/api/billing/plans/{id}`) instead of the dynamic-model convention `/api/dynamic/{model}/{id}`.
 * This lets non-dynamic-model detail pages (billing, finance, any custom REST aggregate) render.
 *
 * Endpoint templating: public pid placeholders and compatibility aliases are replaced
 * with the public record pid. If none is present, `/{recordPid}` is appended for
 * record-scoped detail pages. Singleton API detail pages without a record pid use the
 * endpoint as-is.
 */
export function resolveDetailRecordEndpoint(
  schema: { extension?: Record<string, any>; modelCode?: string } | null | undefined,
  tableName: string,
  recordPid: string,
): { endpoint: string; method: 'get' | 'post' } {
  const ds = schema?.extension?.dataSource as
    | { type?: string; endpoint?: string; method?: string; adaptor?: string; detailEndpoint?: string; recordEndpoint?: string }
    | undefined;
  if (ds && ds.type === 'api' && typeof ds.endpoint === 'string' && ds.endpoint.length > 0) {
    const method = String(ds.method || 'get').toLowerCase() === 'post' ? 'post' : 'get';
    const declaredDetail = ds.detailEndpoint || ds.recordEndpoint;
    const hasExplicitDetail = typeof declaredDetail === 'string' && declaredDetail.trim().length > 0;
    let endpoint = hasExplicitDetail ? declaredDetail.trim() : ds.endpoint;
    const legacyRecordKey = 'record' + 'Id';
    const publicRecordEndpointPlaceholder = new RegExp(
      `\\{recordPid\\}|\\$\\{recordPid\\}|\\{pid\\}|\\$\\{pid\\}|\\{id\\}|\\{${legacyRecordKey}\\}|\\$\\{${legacyRecordKey}\\}`,
      'g',
    );
    if (recordPid && publicRecordEndpointPlaceholder.test(endpoint)) {
      endpoint = endpoint.replace(publicRecordEndpointPlaceholder, encodeURIComponent(recordPid));
    } else if (recordPid && !hasExplicitDetail && ds.adaptor !== 'commandRecord') {
      endpoint = `${endpoint.replace(/\/+$/, '')}/${encodeURIComponent(recordPid)}`;
    } else {
      endpoint = endpoint.replace(/\/+$/, '');
    }
    return { endpoint, method };
  }
  const model = schema?.modelCode || tableName;
  return {
    endpoint: buildDetailRecordEndpoint(model, recordPid),
    method: 'get',
  };
}

export function isSuccessfulDetailResult(result: any): boolean {
  return (
    ResultHelper.isSuccess(result) &&
    result?.success !== false &&
    !(Number(result?.httpStatus) >= 400)
  );
}

/** Shared transport for the initial detail read and post-command refresh. */
export async function fetchDetailRecord(
  schema: any,
  modelCode: string,
  recordPid: string,
  token?: string,
  search = '',
): Promise<any> {
  const { endpoint, method } = resolveDetailRecordEndpoint(schema, modelCode, recordPid);
  const ds = schema?.extension?.dataSource;
  const configuredParams = ds?.type === 'api' ? ds.params : undefined;
  if (
    configuredParams !== undefined &&
    (!configuredParams || typeof configuredParams !== 'object' || Array.isArray(configuredParams))
  )
    throw new Error('Invalid detail data-source parameters');
  if (ds?.adaptor === 'commandRecord' && method !== 'post') {
    throw new Error('Command detail data sources require POST');
  }
  const context = createExpressionContext({
    record: { pid: recordPid },
    row: { pid: recordPid },
    form: { pid: recordPid },
    $page: {
      kind: schema?.kind,
      modelCode: schema?.modelCode || modelCode,
      pageKey: schema?.pageKey,
      recordPid: recordPid || undefined,
    },
    state: { routeContext: decodeRouteContextFromSearch(search) || {} },
  } as any);
  const params =
    configuredParams === undefined
      ? undefined
      : expressionEvaluator.evaluateObject(configuredParams, context);
  const result = await fetchResult(endpoint, {
    method,
    token,
    ...(params === undefined ? {} : { params }),
  });
  if (ds?.adaptor !== 'commandRecord' || !isSuccessfulDetailResult(result)) return result;
  const completed = result.data as any;
  if (
    completed?.phaseReached !== 'completed' ||
    !completed.data ||
    typeof completed.data !== 'object' ||
    Array.isArray(completed.data)
  ) {
    throw new Error('Invalid completed command detail response');
  }
  return { ...result, data: completed.data };
}

/**
 * Unwrap the master-record object from a raw detail API payload using
 * `extension.dataSource.recordPath` (e.g. the `version` key of a PlanVersionDetailDto).
 * Returns the raw payload when no recordPath is configured.
 */
export function unwrapDetailRecord(rawData: any, recordPath?: string): Record<string, any> {
  if (rawData == null) return {};
  if (!recordPath) return typeof rawData === 'object' ? rawData : {};
  const val = getByDataPath(rawData, recordPath);
  return val && typeof val === 'object' ? val : {};
}

export function mergeDetailDisplayFields(
  record: Record<string, any>,
  listRecord: Record<string, any> | undefined | null,
): Record<string, any> {
  if (!listRecord) return record;
  const displayEntries = Object.entries(listRecord).filter(
    ([key, value]) => key.endsWith('_display') && value !== null && value !== undefined,
  );
  if (displayEntries.length === 0) return record;
  return {
    ...record,
    ...Object.fromEntries(displayEntries),
  };
}

export async function resolveDetailListDisplayRecord(
  modelCode: string,
  recordPid: string,
  token?: string,
): Promise<RecordData | undefined> {
  try {
    const result = await fetchResult<DetailListResult>(buildApiEndpoint(modelCode) + '/list', {
      method: 'get',
      params: {
        pageNum: 1,
        pageSize: 1,
        filters: JSON.stringify([{ fieldName: 'pid', operator: 'EQ', value: recordPid }]),
      },
      token: token || undefined,
    });
    if (!ResultHelper.isSuccess(result)) return undefined;
    const records = result.data?.records;
    return Array.isArray(records) ? records[0] : undefined;
  } catch {
    return undefined;
  }
}

export async function enrichDetailRecordDisplayFields(
  modelCode: string,
  recordPid: string,
  record: Record<string, any>,
  token?: string,
): Promise<Record<string, any>> {
  const listRecord = await resolveDetailListDisplayRecord(modelCode, recordPid, token);
  return mergeDetailDisplayFields(record, listRecord);
}

/**
 * Load the complete post-command detail snapshot. The returned promise does not
 * settle until reference display enrichment has also completed, so the next
 * toolbar action sees the refreshed row_version together with its display data.
 */
export async function loadDetailRecordRefreshSnapshot(
  fetchRecord: () => Promise<any>,
  recordPath: string | undefined,
  modelCode: string,
  recordPid: string | undefined,
  token?: string,
  enrichRecord: typeof enrichDetailRecordDisplayFields = enrichDetailRecordDisplayFields,
): Promise<DetailRecordRefreshSnapshot | null> {
  const result = await fetchRecord();
  if (!isSuccessfulDetailResult(result) || !result.data) return null;
  const rawData = result.data as RecordData;
  const unwrappedRecord = unwrapDetailRecord(rawData, recordPath);
  const recordData = recordPid
    ? await enrichRecord(modelCode, recordPid, unwrappedRecord, token)
    : unwrappedRecord;
  return { rawData, recordData };
}

/** Validate only explicit parent identity/version facts before strict flow refresh can complete. */
export function assertStrictDetailRefreshParent(
  raw: unknown,
  recordPath: string | undefined,
  previous: Record<string, any>,
  expectedPid?: string,
): void {
  const parent = getByDataPath(raw, recordPath);
  if (
    !parent ||
    typeof parent !== 'object' ||
    Array.isArray(parent) ||
    ![Object.prototype, null].includes(Object.getPrototypeOf(parent)) ||
    Object.keys(parent).length === 0
  )
    throw new Error('Detail parent record is unavailable');
  if (expectedPid && (typeof parent.pid !== 'string' || parent.pid !== expectedPid))
    throw new Error('Detail parent identity does not match');
  for (const field of ['rowVersion', 'row_version']) {
    if (!Object.prototype.hasOwnProperty.call(previous, field)) continue;
    const value = parent[field];
    // Same number/string -> nonnegative safe-integer contract as registered command actions.
    // Validation never rewrites the original wire value or invents a version field.
    const version =
      typeof value === 'number' || typeof value === 'string'
        ? String(value).trim() === ''
          ? NaN
          : Number(value)
        : NaN;
    if (!Number.isSafeInteger(version) || version < 0)
      throw new Error('Detail parent version is unavailable');
  }
}

export function readDetailRecordField(recordData: RecordData, fieldCode: string): unknown {
  if (!fieldCode) return undefined;
  if (Object.prototype.hasOwnProperty.call(recordData, fieldCode)) {
    return recordData[fieldCode];
  }
  const camelField = fieldCode.replace(/_([a-z])/g, (_match, letter: string) =>
    letter.toUpperCase(),
  );
  if (camelField !== fieldCode && Object.prototype.hasOwnProperty.call(recordData, camelField)) {
    return recordData[camelField];
  }
  const snakeField = fieldCode.replace(/[A-Z]/g, (letter) => `_${letter.toLowerCase()}`);
  if (snakeField !== fieldCode && Object.prototype.hasOwnProperty.call(recordData, snakeField)) {
    return recordData[snakeField];
  }
  return undefined;
}
