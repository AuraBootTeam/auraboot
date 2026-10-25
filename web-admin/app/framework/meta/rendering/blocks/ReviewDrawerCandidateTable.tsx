import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import {
  comparisonStatusFieldClass,
  comparisonStatusForField,
} from './ReviewDrawerCandidateFields';
import { comparisonStatusLabel } from './ReviewDrawerEvidence';
import {
  compactNumber,
  CompactPriceLadder,
  formatUnitPrice,
  ladderUnitPriceAtQuantity,
  numericValue,
  PriceComparison,
  priceFactorPercent,
  PriceLadder,
  scoreText,
  suggestedPurchaseQuantity,
} from './ReviewDrawerPrices';
import {
  candidateKey,
  configuredTone,
  formatConfiguredValue,
  formatValue,
  isEmptyValue,
  localized,
  parseLadderRungs,
  readFieldValue,
  safeExternalUrl,
  toneClass,
} from './reviewDrawerValues';
import { readPath } from './workbenchBlockUtils';

export function tableFieldValue(candidate: any, field: any, referenceRecord?: any): unknown {
  if (field?.referenceField) return readPath(referenceRecord, field.referenceField);
  return readFieldValue(candidate, field, referenceRecord);
}

export function CandidateTableField({
  candidate,
  field,
  referenceRecord,
  testIdPrefix,
  rowKey,
  locale,
  t,
}: {
  candidate: any;
  field: any;
  referenceRecord?: any;
  testIdPrefix: string;
  rowKey: string;
  locale: string;
  t: (key: string) => string;
}) {
  const key = String(field.key || field.field || field.referenceField || field.label);
  const label = getLocalizedText(field.label || key, locale, t);
  const rawValue = tableFieldValue(candidate, field, referenceRecord);
  if (field.hideWhenEmpty && isEmptyValue(rawValue)) return null;
  const variantClass =
    field.variant === 'primary'
      ? 'text-text font-semibold'
      : field.variant === 'emphasis'
        ? 'text-status-red text-base font-semibold tabular-nums'
        : field.variant === 'success'
          ? 'text-status-green font-medium'
          : field.variant === 'warning'
            ? 'text-status-amber font-medium'
            : 'text-text-2';
  const labelNode =
    field.showLabel === false ? null : <span className="text-text-3 mr-1">{label}：</span>;
  const ladder = field.format === 'ladder' ? parseLadderRungs(rawValue) : null;
  const externalUrl = field.format === 'link' ? safeExternalUrl(rawValue) : null;

  if (field.format === 'ladder') {
    return (
      <div data-testid={`${testIdPrefix}-${rowKey}-table-field-${key}`}>
        {ladder ? (
          <CompactPriceLadder
            testIdPrefix={testIdPrefix}
            rowKey={rowKey}
            rungs={ladder}
            factor={tableFieldValue(candidate, { field: field.factorField }, referenceRecord)}
            locale={locale}
            t={t}
          />
        ) : (
          <span className="text-text-3">-</span>
        )}
      </div>
    );
  }

  if (field.format === 'purchase-quantity') {
    const requested = field.requestedQtyField
      ? readPath(candidate, field.requestedQtyField)
      : readPath(referenceRecord, field.requestedQtyReferenceField);
    const requestedNumber = numericValue(requested);
    const moq = readPath(candidate, field.moqField);
    const suggested = suggestedPurchaseQuantity(requested, moq);
    return (
      <div data-testid={`${testIdPrefix}-${rowKey}-table-field-${key}`} className="grid gap-0.5">
        <div>
          <span className="text-text-3">{localized(locale, t, '需求', 'Required')}：</span>
          <span className="text-text font-medium tabular-nums">{formatValue(requested)}</span>
        </div>
        <div>
          <span className="text-text-3">
            {localized(locale, t, '建议采购', 'Suggested order')}：
          </span>
          <span className="text-text font-semibold tabular-nums">
            {suggested === null ? '-' : compactNumber(suggested, 3)}
          </span>
        </div>
        {suggested !== null && requestedNumber !== null && suggested > requestedNumber && (
          <div className="text-status-amber text-[11px]">
            {localized(locale, t, '补齐 MOQ', 'Raised to MOQ')}
          </div>
        )}
      </div>
    );
  }

  if (field.format === 'quote-total') {
    const configuredOriginal = numericValue(rawValue);
    const factorValue = tableFieldValue(candidate, { field: field.factorField }, referenceRecord);
    const configuredFactored = numericValue(
      tableFieldValue(candidate, { field: field.factoredField }, referenceRecord),
    );
    const requested = field.requestedQtyField
      ? readPath(candidate, field.requestedQtyField)
      : readPath(referenceRecord, field.requestedQtyReferenceField);
    const suggested = suggestedPurchaseQuantity(requested, readPath(candidate, field.moqField));
    const ladderOriginal = field.ladderField
      ? ladderUnitPriceAtQuantity(
          parseLadderRungs(readPath(candidate, field.ladderField)),
          suggested,
        )
      : null;
    const original = ladderOriginal ?? configuredOriginal;
    // The persisted factored price can be rounded to the model field's scale. Derive it from the
    // supplier price whenever that source value is available so the review surface preserves the
    // same six-decimal precision used by price adoption and workbook generation.
    const factored =
      original !== null ? original * (priceFactorPercent(factorValue) / 100) : configuredFactored;
    const total = factored !== null && suggested !== null ? factored * suggested : null;
    return (
      <div data-testid={`${testIdPrefix}-${rowKey}-table-field-${key}`} className="grid gap-0.5">
        <div className="text-text-3 text-[11px] tabular-nums">
          {localized(locale, t, '原始', 'Original')} {formatUnitPrice(original)} ×{' '}
          {compactNumber(priceFactorPercent(factorValue), 2)}%
        </div>
        <div className="text-status-red text-base font-semibold tabular-nums">
          {factored === null ? '-' : formatUnitPrice(factored)}
        </div>
        <div className="text-text-2 text-[11px] tabular-nums">
          {localized(locale, t, '小计', 'Subtotal')}{' '}
          {total === null ? '-' : compactNumber(total, 6)}
        </div>
      </div>
    );
  }

  const formatted =
    field.format === 'score'
      ? scoreText(rawValue)
      : Array.isArray(rawValue)
        ? rawValue.map(String).join(' · ')
        : formatConfiguredValue(rawValue, field, locale, t);

  return (
    <div
      data-testid={`${testIdPrefix}-${rowKey}-table-field-${key}`}
      className={`min-w-0 [overflow-wrap:anywhere] ${variantClass}`}
    >
      {labelNode}
      {externalUrl ? (
        <a
          href={externalUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="text-accent inline-flex items-center gap-1 underline underline-offset-2"
          data-testid={`${testIdPrefix}-${rowKey}-link-${key}`}
          onClick={(event) => event.stopPropagation()}
        >
          {getLocalizedText(
            field.linkLabel || { 'zh-CN': '商品页 ↗', en: 'Product page ↗' },
            locale,
            t,
          )}
        </a>
      ) : (
        <span className={field.format === 'score' ? 'text-accent text-xl font-semibold' : ''}>
          {formatted}
          {field.format === 'score' && field.unit !== false ? (
            <span className="text-text-3 ml-0.5 text-[11px] font-normal">
              {localized(locale, t, '分', 'pts')}
            </span>
          ) : null}
        </span>
      )}
    </div>
  );
}

export function CandidateComparisonTable({
  candidates,
  tableConfig,
  selectedKey,
  recommendedKey,
  referenceRecord,
  locale,
  t,
  testIdPrefix,
  keyField,
  confirmableField,
  onSelect,
}: {
  candidates: any[];
  tableConfig: any;
  selectedKey: string;
  recommendedKey?: string;
  referenceRecord?: any;
  locale: string;
  t: (key: string) => string;
  testIdPrefix: string;
  keyField?: string;
  confirmableField?: string;
  onSelect: (candidate: any, key: string) => void;
}) {
  const columns: any[] = Array.isArray(tableConfig?.columns) ? tableConfig.columns : [];
  const minWidth = Math.max(960, Number(tableConfig?.minWidth) || 1560);
  const radioName = `${testIdPrefix}-selection`;
  return (
    <div
      className="min-h-0 max-w-full overflow-x-auto"
      data-testid={`${testIdPrefix}-comparison-table-wrap`}
    >
      <table
        className="w-full table-fixed border-collapse text-xs"
        style={{ minWidth }}
        data-testid={`${testIdPrefix}-comparison-table`}
      >
        <colgroup>
          {columns.map((column) => (
            <col
              key={String(column.key || column.label)}
              style={{ width: Math.max(72, Number(column.width) || 140) }}
            />
          ))}
        </colgroup>
        <thead>
          <tr className="border-border bg-subtle border-b">
            {columns.map((column) => (
              <th
                key={String(column.key || column.label)}
                className="text-text-2 px-3 py-2 text-left text-[11px] font-semibold whitespace-nowrap"
              >
                {getLocalizedText(column.label || column.key, locale, t)}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {candidates.map((candidate, index) => {
            const rowKey = keyField
              ? String(readPath(candidate, keyField) ?? index)
              : candidateKey(candidate, index);
            const active = rowKey === selectedKey;
            const confirmable = confirmableField
              ? Boolean(readPath(candidate, confirmableField))
              : true;
            const choose = () => {
              if (confirmable) onSelect(candidate, rowKey);
            };
            return (
              <tr
                key={rowKey}
                data-testid={`${testIdPrefix}-${rowKey}`}
                data-selected={active ? 'true' : 'false'}
                aria-disabled={!confirmable}
                onClick={(event) => {
                  if ((event.target as HTMLElement).closest('a,button,input')) return;
                  if (window.getSelection()?.toString().trim()) return;
                  choose();
                }}
                className={`border-border border-b transition-colors select-text last:border-b-0 ${
                  active ? 'bg-accent-weak' : 'bg-panel hover:bg-hover'
                } ${confirmable ? 'cursor-pointer' : 'cursor-not-allowed opacity-60'}`}
              >
                {columns.map((column) => {
                  const columnKey = String(column.key || column.label);
                  if (column.kind === 'selection') {
                    const badges: any[] = Array.isArray(column.badges) ? column.badges : [];
                    return (
                      <td key={columnKey} className="px-3 py-3 align-middle">
                        <div className="flex flex-wrap items-center gap-1.5">
                          <input
                            type="radio"
                            name={radioName}
                            checked={active}
                            disabled={!confirmable}
                            aria-label={localized(
                              locale,
                              t,
                              `选择候选 ${index + 1}`,
                              `Select candidate ${index + 1}`,
                            )}
                            onChange={choose}
                            className="accent-accent h-4 w-4"
                          />
                          {recommendedKey && rowKey === recommendedKey && (
                            <span className="rounded-pill bg-accent-weak text-accent px-1.5 py-0.5 text-[10px] font-semibold">
                              {getLocalizedText(
                                column.recommendedLabel || { 'zh-CN': '推荐', en: 'Recommended' },
                                locale,
                                t,
                              )}
                            </span>
                          )}
                          {badges.map((badge) => {
                            const badgeKey = String(badge.key || badge.field || badge.label);
                            const rawValue = tableFieldValue(candidate, badge, referenceRecord);
                            if (badge.hideWhenEmpty && isEmptyValue(rawValue)) return null;
                            const value = formatConfiguredValue(rawValue, badge, locale, t);
                            const tone = configuredTone(badge, rawValue);
                            return (
                              <span
                                key={badgeKey}
                                className={`rounded-pill border px-1.5 py-0.5 text-[10px] font-semibold ${
                                  toneClass[tone] || toneClass.gray
                                }`}
                              >
                                {value}
                              </span>
                            );
                          })}
                        </div>
                      </td>
                    );
                  }
                  if (column.kind === 'action') {
                    return (
                      <td key={columnKey} className="px-3 py-3 text-center align-middle">
                        <button
                          type="button"
                          disabled={!confirmable}
                          onClick={choose}
                          className={`rounded-control border px-2.5 py-1.5 text-xs font-medium ${
                            active
                              ? 'border-accent bg-accent text-white'
                              : 'border-accent bg-panel text-accent hover:bg-accent-weak'
                          } disabled:border-border disabled:text-text-3 disabled:bg-subtle`}
                        >
                          {active
                            ? getLocalizedText(
                                column.selectedLabel || { 'zh-CN': '已选择', en: 'Selected' },
                                locale,
                                t,
                              )
                            : getLocalizedText(
                                confirmable
                                  ? column.selectLabel || { 'zh-CN': '选择', en: 'Select' }
                                  : column.disabledLabel || {
                                      'zh-CN': '不可采用',
                                      en: 'Unavailable',
                                    },
                                locale,
                                t,
                              )}
                        </button>
                      </td>
                    );
                  }
                  const fields: any[] = Array.isArray(column.fields) ? column.fields : [];
                  return (
                    <td key={columnKey} className="px-3 py-3 align-middle">
                      <div className="grid min-w-0 gap-0.5">
                        {fields.map((field) => (
                          <CandidateTableField
                            key={String(
                              field.key || field.field || field.referenceField || field.label,
                            )}
                            candidate={candidate}
                            field={field}
                            referenceRecord={referenceRecord}
                            testIdPrefix={testIdPrefix}
                            rowKey={rowKey}
                            locale={locale}
                            t={t}
                          />
                        ))}
                      </div>
                    </td>
                  );
                })}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

export function CandidateSelectionSummary({
  candidate,
  config,
  referenceRecord,
  locale,
  t,
}: {
  candidate: any;
  config: any;
  referenceRecord?: any;
  locale: string;
  t: (key: string) => string;
}) {
  if (!candidate || !config) return null;
  const title = formatValue(readPath(candidate, config.titleField));
  const fields: any[] = Array.isArray(config.fields) ? config.fields : [];
  return (
    <div data-testid="review-drawer-selection-summary" className="min-w-0 flex-1">
      <div className="text-text truncate text-sm font-semibold">
        {getLocalizedText(config.label || { 'zh-CN': '已选择', en: 'Selected' }, locale, t)}：
        {title}
      </div>
      {fields.length > 0 && (
        <div className="text-text-2 mt-1 flex flex-wrap gap-x-3 gap-y-1 text-xs">
          {fields.map((field) => {
            const key = String(field.key || field.field || field.referenceField || field.label);
            const label = getLocalizedText(field.label || key, locale, t);
            const value = formatConfiguredValue(
              tableFieldValue(candidate, field, referenceRecord),
              field,
              locale,
              t,
            );
            return (
              <span key={key}>
                {label} {value}
              </span>
            );
          })}
        </div>
      )}
    </div>
  );
}

export function CandidateFieldTable({
  rowKey,
  candidate,
  fields,
  badges,
  locale,
  t,
}: {
  rowKey: string;
  candidate: any;
  fields: any[];
  badges: any[];
  locale: string;
  t: (key: string) => string;
}) {
  const hasUsableLadder = fields.some((field: any) => {
    if (field.format !== 'ladder') return false;
    const rungs = parseLadderRungs(readFieldValue(candidate, field));
    return Boolean(rungs && rungs.length >= 2);
  });

  return (
    <div className="mt-2 space-y-2">
      {badges.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {badges.map((badge: any) => {
            const key = String(badge.key || badge.field || badge.label);
            const rawValue = readFieldValue(candidate, badge);
            if (badge.hideWhenEmpty && isEmptyValue(rawValue)) return null;
            const value = formatConfiguredValue(rawValue, badge, locale, t);
            const tone = configuredTone(badge, rawValue);
            return (
              <span
                key={key}
                data-testid={`review-drawer-candidate-${rowKey}-badge-${key}`}
                className={`rounded-pill inline-flex max-w-full border px-2 py-0.5 text-[11px] font-semibold ${
                  toneClass[tone] || toneClass.gray
                }`}
                title={value}
              >
                <span className="truncate">{value}</span>
              </span>
            );
          })}
        </div>
      )}
      <dl className="grid gap-x-3 gap-y-1.5 text-xs sm:grid-cols-2 xl:grid-cols-4">
        {fields.map((field: any) => {
          const key = String(field.key || field.field || field.label);
          const label = getLocalizedText(field.label || key, locale, t);
          const rawValue = readFieldValue(candidate, field);
          const ladder = field.format === 'ladder' ? parseLadderRungs(rawValue) : null;
          if (field.format === 'price-comparison' && hasUsableLadder) return null;
          if (field.format === 'ladder' && (!ladder || ladder.length < 2)) return null;
          if (field.hideWhenEmpty && isEmptyValue(rawValue)) return null;
          const value = formatConfiguredValue(rawValue, field, locale, t);
          const comparisonStatus = comparisonStatusForField(candidate, field);
          const comparisonClass = comparisonStatusFieldClass(comparisonStatus, field);
          return (
            <div
              key={key}
              data-testid={`review-drawer-candidate-${rowKey}-field-${key}`}
              data-comparison-status={comparisonStatus ? String(comparisonStatus) : undefined}
              className={`min-w-0 ${comparisonClass}`}
              title={
                comparisonStatus ? comparisonStatusLabel(comparisonStatus, locale, t) : undefined
              }
            >
              <dt className="text-text-2 min-w-0 truncate" title={label}>
                {label}
                {field.comparisonShowStatus && comparisonStatus && (
                  <span className="ml-1 text-[10px]">· {comparisonStatusLabel(comparisonStatus, locale, t)}</span>
                )}
              </dt>
              <dd className="text-text min-w-0 font-medium" title={value}>
                {field.format === 'price-comparison' ? (
                  <PriceComparison
                    original={rawValue}
                    factored={readFieldValue(candidate, { field: field.factoredField })}
                    factor={readFieldValue(candidate, { field: field.factorField })}
                    locale={locale}
                    t={t}
                  />
                ) : field.format === 'ladder' && ladder ? (
                  <PriceLadder
                    rowKey={rowKey}
                    rungs={ladder}
                    factor={readFieldValue(candidate, { field: field.factorField })}
                    locale={locale}
                    t={t}
                  />
                ) : field.format === 'link' && safeExternalUrl(rawValue) ? (
                  <a
                    href={safeExternalUrl(rawValue) as string}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-accent underline underline-offset-2"
                    data-testid={`review-drawer-candidate-${rowKey}-link-${key}`}
                  >
                    {getLocalizedText(
                      field.linkLabel || { 'zh-CN': '查看', en: 'Open' },
                      locale,
                      t,
                    )}
                  </a>
                ) : (
                  <span className="block truncate">{value}</span>
                )}
              </dd>
            </div>
          );
        })}
      </dl>
    </div>
  );
}
