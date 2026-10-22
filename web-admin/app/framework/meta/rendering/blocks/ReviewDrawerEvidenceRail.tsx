import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import type { ProfiledFieldGroup } from './ReviewDrawerCandidateFields';
import { FieldGroups, FieldRows, RawColumnsPanel } from './ReviewDrawerFields';
import type { RawColumnGroup } from './reviewDrawerValues';
import {
  formatConfiguredValue,
  formatValue,
  localized,
  parseJsonValue,
  readFieldValue,
  resolveDisplayValue,
  sectionLabel,
} from './reviewDrawerValues';
import { SemanticResolutionEvidence } from './SemanticResolutionEvidence';
import { readPath } from './workbenchBlockUtils';

export function ReviewDrawerEvidenceRail({
  hasComparePanel,
  isStackedCompare,
  hasRawComparePanel,
  compareConfig,
  locale,
  t,
  rawFields,
  rawRecord,
  rawColumnGroups,
  resolvedCanonicalFields,
  resolvedCanonicalGroups,
  canonicalRecord,
  record,
  sourceSummaryItems,
  sourceConfig,
  sourceRecord,
  hasSourceDetails,
  sourceResolutions,
  sourceCards,
  sourcePolicies,
  sourceJsonFields,
  hasExportDetails,
  exportFields,
  exportRows,
}: {
  hasComparePanel: boolean;
  isStackedCompare: boolean;
  hasRawComparePanel: boolean;
  compareConfig: any;
  locale: string;
  t: (key: string, vars?: Record<string, any>) => string;
  rawFields: any;
  rawRecord: any;
  rawColumnGroups: RawColumnGroup[];
  resolvedCanonicalFields: any;
  resolvedCanonicalGroups: ProfiledFieldGroup[];
  canonicalRecord: any;
  record: any;
  sourceSummaryItems: any;
  sourceConfig: any;
  sourceRecord: any;
  hasSourceDetails: boolean;
  sourceResolutions: any[];
  sourceCards: any;
  sourcePolicies: any;
  sourceJsonFields: any;
  hasExportDetails: boolean;
  exportFields: any;
  exportRows: any[];
}) {
  return (
    <div className="min-h-0 min-w-0 space-y-3 overflow-auto pr-1">
      {hasComparePanel && (
        <div
          data-testid="review-drawer-tab-compare"
          data-layout-mode={isStackedCompare ? 'stacked' : 'side-by-side'}
          className={`grid min-w-0 gap-3 ${isStackedCompare ? '' : 'lg:grid-cols-2'}`}
        >
          {hasRawComparePanel && (
            <section
              data-testid="review-drawer-raw-panel"
              className="rounded-card border-border bg-panel overflow-hidden border"
            >
              <header className="border-border bg-panel text-text-2 flex items-center justify-between gap-3 border-b px-3 py-2 text-sm font-semibold">
                {sectionLabel(
                  compareConfig.rawTitle ? { title: compareConfig.rawTitle } : null,
                  locale,
                  t,
                  'Raw',
                )}
                <span className="rounded-pill border-status-blue bg-status-blue-bg text-status-blue border px-2 py-0.5 text-xs font-medium">
                  {localized(locale, t, '只读证据', 'Read-only evidence')}
                </span>
              </header>
              {rawFields.length > 0 && (
                <FieldRows fields={rawFields} record={rawRecord} locale={locale} t={t} />
              )}
              {rawColumnGroups.map((group) => (
                <RawColumnsPanel
                  key={group.key}
                  columns={group.columns}
                  config={group.config}
                  panelKey={group.legacyTestId ? undefined : group.key}
                  locale={locale}
                  t={t}
                />
              ))}
            </section>
          )}
          {resolvedCanonicalFields.length > 0 && (
            <section
              data-testid="review-drawer-canonical-panel"
              className="rounded-card border-border bg-panel overflow-hidden border"
            >
              <header className="border-border bg-panel text-text-2 flex items-center justify-between gap-3 border-b px-3 py-2 text-sm font-semibold">
                {sectionLabel(
                  compareConfig.canonicalTitle ? { title: compareConfig.canonicalTitle } : null,
                  locale,
                  t,
                  'Canonical',
                )}
                <span className="rounded-pill border-status-blue bg-status-blue-bg text-status-blue border px-2 py-0.5 text-xs font-medium">
                  {localized(locale, t, '转换结果', 'Canonical result')}
                </span>
              </header>
              {resolvedCanonicalGroups.length > 0 ? (
                <FieldGroups
                  groups={resolvedCanonicalGroups}
                  record={canonicalRecord}
                  fallbackRecord={record}
                  locale={locale}
                  t={t}
                />
              ) : (
                <FieldRows
                  fields={resolvedCanonicalFields}
                  record={canonicalRecord}
                  fallbackRecord={record}
                  locale={locale}
                  t={t}
                />
              )}
            </section>
          )}
        </div>
      )}

      {sourceSummaryItems.length > 0 && (
        <section className="rounded-card border-border bg-panel border p-3">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <h3 className="text-text text-sm font-semibold">
              {getLocalizedText(
                sourceConfig.summary?.title || {
                  'zh-CN': '解析状态摘要',
                  en: 'Parse status summary',
                },
                locale,
                t,
              )}
            </h3>
            {sourceConfig.summary?.description && (
              <span className="text-text-2 max-w-full min-w-0 truncate text-xs">
                {getLocalizedText(sourceConfig.summary.description, locale, t)}
              </span>
            )}
          </div>
          <div data-testid="review-drawer-parse-summary" className="mt-3 flex flex-wrap gap-2">
            {sourceSummaryItems.map((item: any) => {
              const key = String(item.key || item.field || item.label);
              const label = getLocalizedText(item.label || key, locale, t);
              const value = resolveDisplayValue(sourceRecord, item, locale, t);
              return (
                <span
                  key={key}
                  className="rounded-control text-text-2 border-border bg-subtle inline-flex min-h-8 max-w-full items-center gap-1.5 border px-2.5 py-1 text-xs"
                >
                  <span className="text-text-2 shrink-0 font-medium">{label}</span>
                  <span
                    className="text-text max-w-[260px] min-w-0 truncate font-semibold"
                    title={value}
                  >
                    {value}
                  </span>
                </span>
              );
            })}
          </div>
        </section>
      )}

      {hasSourceDetails && (
        <details
          open={sourceConfig.openByDefault === true}
          data-testid="review-drawer-tab-source"
          className="rounded-card border-border bg-panel overflow-hidden border"
        >
          <summary className="bg-panel text-text cursor-pointer px-3 py-2 text-sm font-semibold">
            {getLocalizedText(
              sourceConfig.detailsTitle || {
                'zh-CN': '解析证据与 Profile / LLM Policy',
                en: 'Parse evidence and Profile / LLM policy',
              },
              locale,
              t,
            )}
          </summary>
          <div className="border-border space-y-3 border-t p-3">
            <SemanticResolutionEvidence
              traces={sourceResolutions}
              locale={locale}
              t={t}
              title={sourceConfig.resolutionTitle}
              fieldLabels={sourceConfig.resolutionLabels}
            />
            {sourceCards.length > 0 && (
              <div data-testid="review-drawer-source-cards" className="grid gap-3 md:grid-cols-2">
                {sourceCards.map((card: any) => {
                  const key = String(card.key || card.title || card.valueField);
                  const value = `${formatValue(readFieldValue(sourceRecord, card), card.emptyText)}${
                    card.unit ? String(card.unit) : ''
                  }`;
                  return (
                    <section
                      key={key}
                      data-testid={`review-drawer-source-card-${key}`}
                      className="rounded-card border-border bg-subtle border p-3"
                    >
                      <h3 className="text-text-2 text-xs font-medium">
                        {getLocalizedText(card.title || key, locale, t)}
                      </h3>
                      <div
                        data-testid={`review-drawer-source-card-${key}-value`}
                        title={value}
                        className="text-text mt-2 text-sm font-semibold [overflow-wrap:anywhere]"
                      >
                        {value}
                      </div>
                      {card.description && (
                        <p className="text-text-2 mt-1 text-xs">
                          {getLocalizedText(card.description, locale, t)}
                        </p>
                      )}
                    </section>
                  );
                })}
              </div>
            )}
            {sourcePolicies.length > 0 && (
              <section className="rounded-card border-border bg-panel border p-3">
                <h3 className="text-text mb-3 text-sm font-semibold">
                  {getLocalizedText(
                    sourceConfig.policyTitle || {
                      'zh-CN': 'LLM 行为由 Profile Policy 控制',
                      en: 'LLM behavior is controlled by Profile Policy',
                    },
                    locale,
                    t,
                  )}
                </h3>
                <div className="grid gap-3 md:grid-cols-3">
                  {sourcePolicies.map((policy: any) => (
                    <div
                      key={String(policy.key || policy.title)}
                      className="rounded-control border-border bg-subtle border p-3"
                    >
                      <h4 className="text-text text-sm font-medium">
                        {getLocalizedText(policy.title || policy.key, locale, t)}
                      </h4>
                      <ul className="text-text-2 mt-2 list-disc space-y-1 pl-5 text-xs">
                        {(policy.items || []).map((item: any) => (
                          <li key={String(item)}>{String(item)}</li>
                        ))}
                      </ul>
                    </div>
                  ))}
                </div>
              </section>
            )}
            {sourceConfig.jsonField && (
              <pre
                data-testid="review-drawer-source-json"
                className="rounded-card border-inverse-border bg-inverse text-inverse-muted max-h-64 overflow-auto border p-3 text-xs"
              >
                {JSON.stringify(
                  parseJsonValue(readPath(sourceRecord, sourceConfig.jsonField)),
                  null,
                  2,
                )}
              </pre>
            )}
            {sourceJsonFields.map((item: any) => {
              const key = String(item.key || item.field || item.label);
              return (
                <section key={key} data-testid={`review-drawer-source-json-${key}`}>
                  <div className="text-text-2 mb-1 text-xs font-medium">
                    {getLocalizedText(item.label || key, locale, t)}
                  </div>
                  <pre className="rounded-card border-inverse-border bg-inverse text-inverse-muted max-h-64 overflow-auto border p-3 text-xs">
                    {JSON.stringify(parseJsonValue(readPath(sourceRecord, item.field)), null, 2)}
                  </pre>
                </section>
              );
            })}
          </div>
        </details>
      )}

      {hasExportDetails && (
        <details
          data-testid="review-drawer-tab-export"
          className="rounded-card border-border bg-panel overflow-hidden border"
        >
          <summary className="bg-panel text-text cursor-pointer px-3 py-2 text-sm font-semibold">
            {localized(locale, t, '决策历史与导出影响', 'Decision history and export impact')}
          </summary>
          <div className="border-border space-y-3 border-t p-3">
            {exportFields.length > 0 && (
              <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
                {exportFields.map((field: any) => {
                  const key = String(field.key || field.field || field.label);
                  return (
                    <section key={key} className="rounded-card border-border bg-subtle border p-3">
                      <div className="text-text-2 text-xs">
                        {getLocalizedText(field.label || key, locale, t)}
                      </div>
                      <div className="text-text mt-1 text-sm font-semibold break-words">
                        {formatConfiguredValue(readFieldValue(record, field), field, locale, t)}
                      </div>
                    </section>
                  );
                })}
              </div>
            )}
            {exportRows.length > 0 && (
              <ol className="rounded-card border-border divide-border bg-panel divide-y border">
                {exportRows.map((row: any, index: number) => (
                  <li key={String(row.pid ?? index)} className="text-text-2 px-3 py-2 text-sm">
                    <span className="font-mono font-semibold">
                      {formatValue(readPath(row, 'bom_er_filename'), String(row.pid ?? index))}
                    </span>
                    <span className="text-text-2 ml-2 text-xs">
                      Rev {formatValue(readPath(row, 'bom_er_revision_no'))}
                    </span>
                  </li>
                ))}
              </ol>
            )}
          </div>
        </details>
      )}
    </div>
  );
}
