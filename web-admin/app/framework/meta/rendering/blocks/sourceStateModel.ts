import type {
  AuthoritativeSourceConfig,
  SourceStateText,
  SourceStateTone,
} from '../../schemas/source-state';
import { readOwnPath } from '../../runtime/expression/row-projection';
import { getLocalizedText } from '../../runtime/expression/i18n-renderer';

export type SourceScalar = string | number | boolean;
export interface SourceIdentity {
  key: string;
  expected: Array<{ path: string; value: SourceScalar }>;
}
export interface SourceView {
  value?: string;
  unit?: string;
  statuses: Array<{ label: string; value: string; tone: SourceStateTone }>;
  metadata: Array<{ label: string; value: string }>;
  points?: Array<[number, number | null]>;
  empty?: boolean;
  suppressed?: boolean;
}
function fail(): never {
  throw new Error('Invalid authoritative source data or binding');
}
function object(value: unknown): value is Record<string, unknown> {
  return (
    !!value &&
    typeof value === 'object' &&
    !Array.isArray(value) &&
    [Object.prototype, null].includes(Object.getPrototypeOf(value))
  );
}
function path(value: unknown): void {
  if (typeof value !== 'string') fail();
  readOwnPath({}, value);
}
function scalar(value: unknown): value is SourceScalar {
  return (
    (typeof value === 'string' && !!value.trim()) ||
    typeof value === 'boolean' ||
    (typeof value === 'number' && Number.isFinite(value))
  );
}
export function validateSourceBinding(config: AuthoritativeSourceConfig): void {
  if (
    !object(config) ||
    config.mode !== 'authoritative' ||
    !Array.isArray(config.identity) ||
    !config.identity.length
  )
    fail();
  for (const item of config.identity) {
    path(item.responsePath);
    path(item.contextPath);
  }
  for (const item of config.scopePaths ?? []) path(item);
  if (
    config.precision !== undefined &&
    (!Number.isInteger(config.precision) || config.precision < 0 || config.precision > 12)
  )
    fail();
  if (config.unitPath !== undefined) path(config.unitPath);
  if (config.series) {
    const series = config.series;
    for (const item of [series.pointsPath, series.timePath, series.valuePath]) path(item);
    if (!['line', 'scatter'].includes(series.kind)) fail();
    for (const item of series.identity ?? []) {
      path(item.pointPath);
      path(item.responsePath);
    }
    for (const item of series.gapWhen ?? []) {
      path(item.path);
      if (!Array.isArray(item.values) || !item.values.length || !item.values.every(scalar)) fail();
      if (
        !Array.isArray(item.knownValues) ||
        !item.knownValues.length ||
        !item.knownValues.every(scalar) ||
        !item.values.every((value) => item.knownValues.includes(value))
      )
        fail();
    }
    if (
      series.domain &&
      (series.domain.length !== 2 ||
        !series.domain.every(Number.isFinite) ||
        series.domain[0] >= series.domain[1])
    )
      fail();
    if (
      series.height !== undefined &&
      (!Number.isFinite(series.height) || series.height < 120 || series.height > 1200)
    )
      fail();
    if (series.color !== undefined && !/^#[0-9a-f]{6}$/i.test(series.color)) fail();
  } else path(config.valuePath);
  for (const field of config.metadata ?? []) {
    path(field.path);
    if (field.format !== undefined && !['text', 'datetime'].includes(field.format)) fail();
  }
  for (const state of config.states ?? []) {
    path(state.path);
    if (!object(state.values) || !Object.keys(state.values).length) fail();
    for (const value of Object.values(state.values)) {
      if (
        !object(value) ||
        (value.tone !== undefined &&
          !['neutral', 'success', 'warning', 'danger'].includes(String(value.tone)))
      )
        fail();
      if (value.hideValue !== undefined && typeof value.hideValue !== 'boolean') fail();
    }
  }
}

/** The key stays internal; it must never become UI copy or a DOM identity attribute. */
export function readSourceIdentity(
  config: AuthoritativeSourceConfig,
  context: unknown,
): SourceIdentity | null {
  validateSourceBinding(config);
  const tenant = readOwnPath(context, 'global.tenant.id'),
    user = readOwnPath(context, 'global.user.id');
  if (!scalar(tenant) || !scalar(user)) return null;
  const expected = config.identity.map((item) => ({
    path: item.responsePath,
    value: readOwnPath(context, item.contextPath),
  }));
  const scopes = (config.scopePaths ?? []).map((item) => readOwnPath(context, item));
  if (expected.some((item) => !scalar(item.value)) || scopes.some((item) => !scalar(item)))
    return null;
  const permissions = readOwnPath(context, 'global.user.permissions');
  return {
    key: JSON.stringify([tenant, user, permissions, expected, scopes]),
    expected: expected as SourceIdentity['expected'],
  };
}
function text(value: SourceStateText, locale: string, t: (key: string) => string): string {
  const result = getLocalizedText(value, locale, t);
  if (!result.trim()) fail();
  return result;
}
function date(value: unknown, locale: string): string {
  if (typeof value !== 'string' || !/^\d{4}-\d\d-\d\dT.+(?:Z|[+-]\d\d:\d\d)$/.test(value)) fail();
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) fail();
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'medium' }).format(
    timestamp,
  );
}

/** Interpret exactly the selected DMS result; no row, inline, clock or quality fallback. */
export function readSourceView(
  data: unknown,
  config: AuthoritativeSourceConfig,
  identity: SourceIdentity,
  locale: string,
  t: (key: string) => string,
): SourceView {
  if (!object(data)) fail();
  for (const expected of identity.expected)
    if (readOwnPath(data, expected.path) !== expected.value) fail();
  const statuses: SourceView['statuses'] = [];
  let hidden: string | undefined;
  for (const state of config.states ?? []) {
    const raw = readOwnPath(data, state.path);
    if (!scalar(raw)) fail();
    const descriptor = Object.getOwnPropertyDescriptor(state.values, String(raw));
    if (!descriptor || !('value' in descriptor)) fail();
    const presentation = descriptor.value;
    const value = text(presentation.label, locale, t);
    statuses.push({
      label: text(state.label, locale, t),
      value,
      tone: presentation.tone ?? 'neutral',
    });
    if (presentation.hideValue) hidden ??= value;
  }
  const metadata: SourceView['metadata'] = [];
  for (const field of config.metadata ?? []) {
    const raw = readOwnPath(data, field.path);
    let value: string;
    if (raw === null || raw === undefined) {
      if (!field.optional) fail();
      value = '—';
    } else if (field.valueLabels) {
      const descriptor = Object.getOwnPropertyDescriptor(field.valueLabels, String(raw));
      if (!descriptor || !('value' in descriptor)) fail();
      value = text(descriptor.value, locale, t);
    } else if (field.format === 'datetime') value = date(raw, locale);
    else {
      if (!scalar(raw)) fail();
      value = String(raw);
    }
    metadata.push({ label: text(field.label, locale, t), value });
  }
  const view: SourceView = { statuses, metadata };
  if (hidden) return { ...view, value: hidden, suppressed: true };
  if (config.unitPath) {
    const unit = readOwnPath(data, config.unitPath);
    if (typeof unit !== 'string' || !unit.trim()) fail();
    view.unit = unit;
  }
  if (config.series) {
    const series = config.series,
      rows = readOwnPath(data, series.pointsPath);
    if (!Array.isArray(rows) || rows.length > 2000) fail();
    let previous = -Infinity;
    view.points = rows.map((row) => {
      if (!object(row)) fail();
      for (const item of series.identity ?? []) {
        const expected = readOwnPath(data, item.responsePath);
        if (!scalar(expected) || readOwnPath(row, item.pointPath) !== expected) fail();
      }
      const at = readOwnPath(row, series.timePath);
      date(at, locale);
      const timestamp = Date.parse(at as string);
      if (timestamp < previous) fail();
      previous = timestamp;
      const raw = readOwnPath(row, series.valuePath);
      const gapStates = (series.gapWhen ?? []).map((rule) => {
        const state = readOwnPath(row, rule.path);
        if (!scalar(state) || !rule.knownValues.includes(state)) fail();
        return rule.values.includes(state);
      });
      const classifiedGap = gapStates.some(Boolean);
      if (raw === undefined && !classifiedGap) fail();
      const gap = raw === null || classifiedGap;
      if (!gap && (typeof raw !== 'number' || !Number.isFinite(raw))) fail();
      return [timestamp, gap ? null : (raw as number)];
    });
    view.empty = rows.length === 0;
    return view;
  }
  const value = readOwnPath(data, config.valuePath!);
  if (config.valueLabels) {
    const descriptor = Object.getOwnPropertyDescriptor(config.valueLabels, String(value));
    if (!descriptor || !('value' in descriptor)) fail();
    view.value = text(descriptor.value, locale, t);
  } else {
    if (
      !(typeof value === 'number' && Number.isFinite(value)) &&
      !(typeof value === 'string' && /^-?(?:\d+\.?\d*|\.\d+)$/.test(value))
    )
      fail();
    view.value =
      config.precision === undefined
        ? String(value)
        : new Intl.NumberFormat(locale, { maximumFractionDigits: config.precision }).format(
            Number(value),
          );
  }
  return view;
}
