import { formatValue, type LadderRung, localized } from './reviewDrawerValues';

export function priceFactorPercent(value: unknown): number {
  const normalized = String(value ?? '')
    .trim()
    .replace(/%$/, '');
  const parsed = Number(normalized);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 100;
}

export function formatUnitPrice(value: unknown): string {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return formatValue(value);
  return parsed
    .toFixed(6)
    .replace(/(\.\d*?[1-9])0+$/, '$1')
    .replace(/\.0+$/, '');
}

export function factoredUnitPrice(value: unknown, factor: unknown): string {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return formatValue(value);
  return formatUnitPrice((parsed * priceFactorPercent(factor)) / 100);
}

export function ladderRangeLabel(rungs: LadderRung[], index: number): string {
  const explicit = String(rungs[index]?.rangeLabel ?? '').trim();
  if (explicit) return explicit;
  const current = Number(rungs[index]?.qty);
  if (!Number.isFinite(current)) return `${String(rungs[index]?.qty ?? '')}+`;
  const next = rungs
    .map((rung) => Number(rung.qty))
    .filter((qty) => Number.isFinite(qty) && qty > current)
    .sort((left, right) => left - right)[0];
  return next === undefined ? `${current}+` : `${current}–${next - 1}`;
}

export function PriceComparison({
  original,
  factored,
  factor,
  locale,
  t,
}: {
  original: unknown;
  factored: unknown;
  factor: unknown;
  locale: string;
  t: (key: string) => string;
}) {
  const factorText = `${Number(priceFactorPercent(factor).toFixed(2))}%`;
  const displayedFactored = Number.isFinite(Number(original))
    ? factoredUnitPrice(original, factor)
    : formatUnitPrice(factored);
  return (
    <div
      data-testid="review-drawer-price-comparison"
      className="rounded-control border-border bg-subtle grid grid-cols-[minmax(0,1fr)_auto_minmax(0,1fr)] items-stretch gap-2 border p-2"
    >
      <div className="min-w-0 px-1">
        <div className="text-text-2 text-[11px]">
          {localized(locale, t, '原始单价', 'Supplier price')}
        </div>
        <div className="text-text mt-1 font-mono text-sm font-medium tabular-nums">
          {formatUnitPrice(original)}
        </div>
      </div>
      <div className="text-text-2 flex flex-col items-center justify-center gap-1 px-1">
        <span className="rounded-pill border-border bg-panel border px-2 py-0.5 text-[10px] font-semibold whitespace-nowrap">
          × {factorText}
        </span>
        <span aria-hidden="true">→</span>
      </div>
      <div className="border-accent bg-accent-weak min-w-0 rounded border px-2 py-1.5">
        <div className="text-accent text-[11px] font-medium">
          {localized(locale, t, '系数后单价', 'After factor')}
        </div>
        <div className="text-text mt-1 font-mono text-base font-semibold tabular-nums">
          {displayedFactored}
        </div>
      </div>
    </div>
  );
}

export function PriceLadder({
  rowKey,
  rungs,
  factor,
  locale,
  t,
}: {
  rowKey: string;
  rungs: LadderRung[];
  factor: unknown;
  locale: string;
  t: (key: string) => string;
}) {
  const factorText = `${Number(priceFactorPercent(factor).toFixed(2))}%`;
  const currentIndex = rungs.findIndex((rung) => rung.current);
  const selectedIndex = currentIndex >= 0 ? currentIndex : 0;
  const selected = rungs[selectedIndex];
  return (
    <div
      className="border-border bg-subtle rounded-control w-full overflow-hidden border text-xs tabular-nums"
      data-testid={`review-drawer-candidate-${rowKey}-ladder`}
    >
      <div
        data-testid={`review-drawer-candidate-${rowKey}-ladder-summary`}
        className="border-border bg-accent-weak text-text flex flex-wrap items-center justify-between gap-2 border-b px-2 py-2"
      >
        <span className="font-medium">
          {localized(locale, t, '当前数量区间', 'Current quantity range')}{' '}
          {ladderRangeLabel(rungs, selectedIndex)}
        </span>
        <span className="font-mono font-semibold">
          {formatUnitPrice(selected.price)} × {factorText} →{' '}
          {factoredUnitPrice(selected.price, factor)}
        </span>
      </div>
      <div className="text-text-2 border-border grid grid-cols-[minmax(72px,0.75fr)_minmax(92px,1fr)_minmax(112px,1fr)] border-b px-2 py-1.5 text-[11px] font-medium">
        <span>{localized(locale, t, '数量区间', 'Qty range')}</span>
        <span className="text-right">{localized(locale, t, '原始单价', 'Original')}</span>
        <span className="text-right">
          {localized(locale, t, '系数后', 'After factor')} ({factorText})
        </span>
      </div>
      {rungs.map((rung, index) => (
        <div
          key={String(rung.qty)}
          data-testid={rung.current ? `review-drawer-ladder-current-${rowKey}` : undefined}
          className={`border-border grid grid-cols-[minmax(72px,0.75fr)_minmax(92px,1fr)_minmax(112px,1fr)] items-center border-b px-2 py-1.5 last:border-b-0 ${
            rung.current ? 'bg-accent-weak text-text font-semibold' : 'text-text-2'
          }`}
        >
          <span className="flex items-center gap-1.5 whitespace-nowrap">
            {ladderRangeLabel(rungs, index)}
            {rung.current && (
              <span className="rounded-pill bg-accent px-1.5 py-0.5 text-[10px] font-semibold text-white">
                {localized(locale, t, '当前', 'Current')}
              </span>
            )}
          </span>
          <span className="text-right font-mono whitespace-nowrap">
            {formatUnitPrice(rung.price)}
          </span>
          <span className="text-text text-right font-mono whitespace-nowrap">
            {factoredUnitPrice(rung.price, factor)}
          </span>
        </div>
      ))}
    </div>
  );
}

export function numericValue(value: unknown): number | null {
  if (value === undefined || value === null || value === '') return null;
  const numeric = typeof value === 'number' ? value : Number(String(value).replace(/[%,$]/g, ''));
  return Number.isFinite(numeric) ? numeric : null;
}

export function compactNumber(value: number, maximumFractionDigits = 6): string {
  return new Intl.NumberFormat('en-US', {
    maximumFractionDigits,
    useGrouping: true,
  }).format(value);
}

export function scoreText(value: unknown): string {
  const numeric = numericValue(value);
  if (numeric === null) return formatValue(value);
  const score = Math.abs(numeric) <= 1 ? numeric * 100 : numeric;
  return compactNumber(score, 2);
}

export function suggestedPurchaseQuantity(
  requestedValue: unknown,
  moqValue: unknown,
): number | null {
  const requested = numericValue(requestedValue);
  const moq = numericValue(moqValue);
  if (requested === null && moq === null) return null;
  return Math.max(requested || 0, moq || 0);
}

export function ladderUnitPriceAtQuantity(
  rungs: LadderRung[] | null,
  quantity: number | null,
): number | null {
  if (!rungs || rungs.length === 0 || quantity === null) return null;
  const pricedRungs = rungs
    .map((rung) => ({ qty: numericValue(rung.qty), price: numericValue(rung.price) }))
    .filter(
      (rung): rung is { qty: number; price: number } => rung.qty !== null && rung.price !== null,
    )
    .sort((left, right) => left.qty - right.qty);
  if (pricedRungs.length === 0) return null;
  let selected = pricedRungs[0];
  for (const rung of pricedRungs) {
    if (rung.qty > quantity) break;
    selected = rung;
  }
  return selected.price;
}

export function CompactPriceLadder({
  testIdPrefix,
  rowKey,
  rungs,
  factor,
  locale,
  t,
}: {
  testIdPrefix: string;
  rowKey: string;
  rungs: LadderRung[];
  factor: unknown;
  locale: string;
  t: (key: string) => string;
}) {
  return (
    <div
      className="grid gap-0.5 text-[11px] tabular-nums"
      data-testid={`${testIdPrefix}-${rowKey}-compact-ladder`}
    >
      {rungs.map((rung, index) => (
        <div
          key={`${String(rung.qty)}-${index}`}
          data-testid={
            rung.current ? `${testIdPrefix}-${rowKey}-compact-ladder-current` : undefined
          }
          className={`grid grid-cols-[minmax(74px,0.8fr)_minmax(76px,1fr)_auto] items-center gap-1.5 whitespace-nowrap ${
            rung.current ? 'text-accent font-semibold' : 'text-text-2'
          }`}
        >
          <span>{ladderRangeLabel(rungs, index)}</span>
          <span className="font-mono">{factoredUnitPrice(rung.price, factor)}</span>
          {rung.current ? (
            <span className="rounded-control bg-accent-weak text-accent px-1 py-0.5 text-[10px]">
              {localized(locale, t, '当前', 'Current')}
            </span>
          ) : (
            <span />
          )}
        </div>
      ))}
    </div>
  );
}
