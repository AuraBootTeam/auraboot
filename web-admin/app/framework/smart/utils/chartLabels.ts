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

/**
 * Display text for a dimension value.
 *
 * `field` is the dimension's column name; `value` is the raw cell. Returns the
 * dict label when one is known, else the value as-is.
 *
 * Time-bucketed dimensions (`col__month`) are formatted server-side into a stable
 * `YYYY-MM` label via to_char — not here — because only the DB knows the session
 * zone, and a raw DATE_TRUNC timestamp rendered client-side lands a month early.
 */
export function dimensionLabel(
  meta: QueryMeta | undefined,
  field: string | undefined,
  value: unknown,
): string {
  return valueLabel(meta?.dimensionLabels, field, value);
}

/** Display text for a metric (series) name. Returns the configured label, else the alias. */
export function metricLabel(metricLabels: MetricLabels | undefined, key: string): string {
  return metricLabels?.[key] ?? key;
}
