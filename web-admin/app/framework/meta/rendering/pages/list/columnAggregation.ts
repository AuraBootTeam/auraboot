/**
 * columnAggregation — pure helpers for the list table's column summary/footer
 * row (T10). Computes per-column aggregates (sum / avg / count / min / max)
 * over the rows currently rendered on the page, plus a formatter that mirrors
 * the body cell's number/currency presentation.
 *
 * SCOPE NOTE: aggregates are computed over the CURRENT page's rows only. A true
 * cross-page (whole result set) total would require a dedicated backend
 * aggregation endpoint and is intentionally out of scope here — the summary row
 * is a page-level convenience, not an authoritative grand total.
 *
 * Spec: auraboot-enterprise/docs/standards/core/ux-design-system.md §3 (list/CRUD).
 */

export type AggregateKind = 'sum' | 'avg' | 'count' | 'min' | 'max';

const VALID_KINDS: ReadonlySet<string> = new Set<AggregateKind>([
  'sum',
  'avg',
  'count',
  'min',
  'max',
]);

export function isAggregateKind(value: unknown): value is AggregateKind {
  return typeof value === 'string' && VALID_KINDS.has(value);
}

/**
 * Coerce a cell value to a finite number, tolerating display-formatted strings
 * such as "12,345.67", "¥1,200" or "$1,000.50". Returns null for nullish,
 * empty, boolean, or otherwise non-numeric input so callers can skip it.
 *
 * Booleans intentionally coerce to null (not 0/1): a boolean column should
 * never silently contribute to a numeric sum/avg.
 */
export function coerceNumeric(value: unknown): number | null {
  if (value === null || value === undefined) return null;
  if (typeof value === 'boolean') return null;
  if (typeof value === 'number') {
    return Number.isFinite(value) ? value : null;
  }
  if (typeof value === 'string') {
    const trimmed = value.trim();
    if (trimmed === '') return null;
    // Strip currency symbols / grouping separators / whitespace, keep digits,
    // sign, decimal point and exponent. "¥1,200" → "1200", "12,345.67" → "12345.67".
    const cleaned = trimmed.replace(/[^0-9.eE+-]/g, '');
    if (cleaned === '' || cleaned === '-' || cleaned === '+' || cleaned === '.') return null;
    const parsed = Number(cleaned);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

/**
 * Whether a value counts as "non-null" for the `count` aggregate: present and
 * not an empty/whitespace-only string. 0 and false DO count.
 */
function isPresent(value: unknown): boolean {
  if (value === null || value === undefined) return false;
  if (typeof value === 'string') return value.trim() !== '';
  return true;
}

/**
 * Aggregate a single column over the given rows.
 *
 * - sum / avg / min / max operate on numerically-coercible values only;
 *   non-numeric values are skipped. Returns null when no numeric value exists.
 * - count counts non-null/non-empty values of ANY type. Returns 0 for empty
 *   input (a concrete count), never null.
 *
 * Returns null for an unknown aggregate kind.
 */
export function aggregateColumn(
  rows: ReadonlyArray<Record<string, unknown>>,
  field: string,
  kind: AggregateKind,
): number | null {
  if (!isAggregateKind(kind)) return null;

  if (kind === 'count') {
    let count = 0;
    for (const row of rows) {
      if (isPresent(row?.[field])) count += 1;
    }
    return count;
  }

  const numbers: number[] = [];
  for (const row of rows) {
    const n = coerceNumeric(row?.[field]);
    if (n !== null) numbers.push(n);
  }
  if (numbers.length === 0) return null;

  switch (kind) {
    case 'sum':
      return numbers.reduce((acc, n) => acc + n, 0);
    case 'avg':
      return numbers.reduce((acc, n) => acc + n, 0) / numbers.length;
    case 'min':
      return numbers.reduce((acc, n) => (n < acc ? n : acc), numbers[0]!);
    case 'max':
      return numbers.reduce((acc, n) => (n > acc ? n : acc), numbers[0]!);
    default:
      return null;
  }
}

export interface AggregateFormatOptions {
  /** Column value type — 'currency' formats with a currency style. */
  valueType?: string;
  /** ISO 4217 currency code (default 'cny'). */
  currencyCode?: string;
  /** Aggregate kind — `count` renders as an integer regardless of valueType. */
  kind?: AggregateKind;
  /** Max fraction digits for non-currency numbers (default 2). */
  precision?: number;
}

/**
 * Format an aggregate value to mirror the body cell's presentation:
 * - currency columns → Intl currency formatting with the column's currencyCode
 * - count → integer
 * - otherwise → grouped decimal (thousands separators, up to `precision` digits)
 *
 * Returns an em dash ("—") for null/undefined so the footer cell is never blank
 * for an aggregated column that simply had no numeric data.
 */
export function formatAggregateValue(
  value: number | null | undefined,
  options: AggregateFormatOptions | undefined,
  locale: string,
): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—';

  const loc = locale || 'en';
  const kind = options?.kind;

  if (kind === 'count') {
    return new Intl.NumberFormat(loc, { maximumFractionDigits: 0 }).format(value);
  }

  if (options?.valueType === 'currency') {
    const currency = options.currencyCode || 'cny';
    try {
      return new Intl.NumberFormat(loc, {
        style: 'currency',
        currency,
        minimumFractionDigits: options.precision ?? 2,
        maximumFractionDigits: options.precision ?? 2,
      }).format(value);
    } catch {
      // Invalid currency code → fall through to plain number formatting.
    }
  }

  return new Intl.NumberFormat(loc, {
    maximumFractionDigits: options?.precision ?? 2,
  }).format(value);
}
