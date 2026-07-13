/**
 * Display labels for chart categories and series.
 *
 * Charts render two kinds of machine codes that a user should never see:
 *
 *   - **dimension values** — the stored dict code (`closed_won`, `call`). The dict
 *     holds the label; `useChartData` resolves it into `meta.dimensionLabels`.
 *   - **metric names** — the series name is the metric alias, and aliases are
 *     constrained to ASCII identifiers by the backend (`AggregateQueryServiceImpl`
 *     validates them against an identifier pattern), so a legend reads
 *     `pipeline_amount` rather than 商机总额. There is nothing to derive it from:
 *     the widget has to supply the label, via `metricLabels`.
 *
 * Both helpers fall back to the raw code, which is what charts showed before this
 * existed — a missing dict or an unlabelled metric degrades, it does not break.
 */

import type { QueryMeta } from '~/framework/smart/types/chart';

/** Metric alias -> display label, supplied by widget config (`visualization.metricLabels`). */
export type MetricLabels = Record<string, string>;

/** Dimension field -> (raw value -> display label). */
export type DimensionLabels = Record<string, Record<string, string>>;

/**
 * Display text for a value in a dict-coded column, given the resolved label map.
 *
 * Use this where there is no `QueryMeta` to hand — the table chart's model-list
 * branch fetches rows directly and never builds one.
 */
export function valueLabel(
  labels: DimensionLabels | undefined,
  field: string | undefined,
  value: unknown,
): string {
  const raw = value == null ? '' : String(value);
  if (!field || !raw) return raw;
  return labels?.[field]?.[raw] ?? raw;
}

/** Grains a dimension may be bucketed by (mirrors the backend's ALLOWED_GRAINS). */
const GRAINS = new Set(['day', 'week', 'month', 'quarter', 'year']);

/**
 * Format a time-bucketed value for display.
 *
 * Aggregate time bucketing returns the raw DATE_TRUNC timestamp
 * (`2025-04-01 00:00:00+08`), which is unreadable on an axis. The grain lives in the
 * dimension name (`col__month`), so no extra metadata is needed to know how to
 * shorten it: month/quarter/year collapse to `YYYY-MM` / `YYYY-Qn` / `YYYY`, finer
 * grains to the date. Non-timestamp values pass through untouched.
 */
export function formatBucketValue(field: string, value: unknown): string {
  const raw = value == null ? '' : String(value);
  const sep = field.indexOf('__');
  if (sep < 0) return raw;
  const grain = field.slice(sep + 2).toLowerCase();
  if (!GRAINS.has(grain) || !raw) return raw;

  // Parse the leading YYYY-MM-DD; the value is a DATE_TRUNC boundary, so day/time
  // components below the grain are already zeroed.
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(raw);
  if (!m) return raw;
  const [, year, month, day] = m;
  switch (grain) {
    case 'year':
      return year;
    case 'quarter':
      return `${year}-Q${Math.floor((Number(month) - 1) / 3) + 1}`;
    case 'month':
      return `${year}-${month}`;
    default:
      return `${year}-${month}-${day}`;
  }
}

/**
 * Display text for a dimension value.
 *
 * `field` is the dimension's column name; `value` is the raw cell. Resolves, in
 * order: a dict label, a formatted time bucket (for `col__grain` dimensions), else
 * the value as-is.
 */
export function dimensionLabel(
  meta: QueryMeta | undefined,
  field: string | undefined,
  value: unknown,
): string {
  const dict = valueLabel(meta?.dimensionLabels, field, value);
  // If a dict label applied it wins; otherwise try time-bucket formatting.
  if (field && dict === (value == null ? '' : String(value))) {
    return formatBucketValue(field, value);
  }
  return dict;
}

/** Display text for a metric (series) name. Returns the configured label, else the alias. */
export function metricLabel(metricLabels: MetricLabels | undefined, key: string): string {
  return metricLabels?.[key] ?? key;
}
