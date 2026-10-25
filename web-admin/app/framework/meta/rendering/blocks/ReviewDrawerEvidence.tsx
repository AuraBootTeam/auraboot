import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { normalizeComparisonRecord } from './ReviewDrawerCandidateFields';
import { formatValue, localized, parseJsonValue } from './reviewDrawerValues';
import { readPath } from './workbenchBlockUtils';

export function isComparisonRecord(value: unknown): value is Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  return (
    Object.prototype.hasOwnProperty.call(value, 'status') ||
    Object.prototype.hasOwnProperty.call(value, 'sourceValue') ||
    Object.prototype.hasOwnProperty.call(value, 'candidateValue') ||
    Object.prototype.hasOwnProperty.call(value, 'reason')
  );
}

export function isComparisonList(value: unknown): value is Record<string, unknown>[] {
  return Array.isArray(value) && value.length > 0 && value.every(isComparisonRecord);
}

export function comparisonStatusLabel(
  status: unknown,
  locale: string,
  t: (key: string) => string,
): string {
  const text = String(status ?? '');
  const labels: Record<string, { 'zh-CN': string; en: string }> = {
    unknown: { 'zh-CN': '未收录，待核实', en: 'Unknown, Review Required' },
    source_conflict: { 'zh-CN': '原始信息冲突', en: 'Source Conflict' },
    matched: { 'zh-CN': '一致', en: 'Matched' },
    mismatch: { 'zh-CN': '不一致', en: 'Mismatch' },
    missing: { 'zh-CN': '证据缺失', en: 'Evidence Missing' },
    missing_source: { 'zh-CN': '原始缺失', en: 'Source Missing' },
    missing_candidate: { 'zh-CN': '候选缺失', en: 'Candidate Missing' },
    missing_both: { 'zh-CN': '双方缺失', en: 'Both Missing' },
  };
  return getLocalizedText(labels[text] || text, locale, t);
}

export function comparisonStatusClass(status: unknown): string {
  switch (String(status ?? '')) {
    case 'matched':
      return 'border-status-green bg-status-green-bg text-status-green';
    case 'mismatch':
      return 'border-status-red bg-status-red-bg text-status-red';
    case 'missing_source':
    case 'missing_candidate':
    case 'missing_both':
      return 'border-status-amber bg-status-amber-bg text-status-amber';
    default:
      return 'border-status-gray bg-status-gray-bg text-status-gray';
  }
}

export function ComparisonList({
  value,
  locale,
  t,
}: {
  value: Record<string, unknown>[];
  locale: string;
  t: (key: string) => string;
}) {
  return (
    <div className="space-y-2">
      {value.map((comparison, index) => {
        const key =
          humanizeComparisonField(comparison.label ?? comparison.key, locale, t) || `#${index + 1}`;
        const sourceValue = formatValue(comparison.sourceValue, '');
        const candidateValue = formatValue(comparison.candidateValue, '');
        const reason = formatValue(comparison.reason, '');
        return (
          <section
            key={`${key}-${index}`}
            data-testid={`review-drawer-comparison-${index}`}
            className="rounded-control border-border bg-panel overflow-hidden border"
          >
            <div className="border-border flex flex-wrap items-center justify-between gap-2 border-b px-2.5 py-1.5">
              <span className="text-text min-w-0 text-xs font-semibold break-words">{key}</span>
              <span
                className={`rounded-pill shrink-0 border px-2 py-0.5 text-[11px] font-semibold ${comparisonStatusClass(
                  comparison.status,
                )}`}
              >
                {comparisonStatusLabel(comparison.status, locale, t)}
              </span>
            </div>
            <div className="grid gap-2 px-2.5 py-2 text-xs md:grid-cols-2">
              <div className="min-w-0">
                <div className="text-text-2 font-medium">
                  {localized(locale, t, '原始', 'Source')}
                </div>
                <div className="text-text mt-0.5 break-words">{sourceValue || '-'}</div>
              </div>
              <div className="min-w-0">
                <div className="text-text-2 font-medium">
                  {localized(locale, t, '候选', 'Candidate')}
                </div>
                <div className="text-text mt-0.5 break-words">{candidateValue || '-'}</div>
              </div>
              {reason && (
                <details className="text-text-2 min-w-0 break-words md:col-span-2">
                  <summary>{localized(locale, t, '技术详情', 'Technical details')}</summary>
                  <div>
                    {localized(locale, t, '原因代码', 'Reason code')}: {reason}
                  </div>
                </details>
              )}
            </div>
          </section>
        );
      })}
    </div>
  );
}

export const comparisonFieldLabels: Record<string, { 'zh-CN': string; en: string }> = {
  brand: { 'zh-CN': '品牌', en: 'Brand' },
  mpn: { 'zh-CN': 'MPN', en: 'MPN' },
  package: { 'zh-CN': '封装', en: 'Package' },
  packageCode: { 'zh-CN': '封装', en: 'Package' },
  capacitance: { 'zh-CN': '容值', en: 'Capacitance' },
  resistance: { 'zh-CN': '阻值', en: 'Resistance' },
  voltage: { 'zh-CN': '耐压', en: 'Voltage' },
  tolerance: { 'zh-CN': '误差', en: 'Tolerance' },
  dielectric: { 'zh-CN': '介质', en: 'Dielectric' },
  frequency: { 'zh-CN': '频率', en: 'Frequency' },
};

export function humanizeComparisonField(
  value: unknown,
  locale: string,
  t: (key: string) => string,
): string {
  const text = String(value ?? '');
  return text ? getLocalizedText(comparisonFieldLabels[text] || text, locale, t) : '';
}

export function collectEvidenceComparisons(candidate: any): Record<string, unknown>[] {
  const evidence = parseJsonValue(readPath(candidate, 'bom_me_evidence_json'));
  const groups = readPath(evidence, 'groups');
  const comparisons: Record<string, unknown>[] = [];
  if (groups && typeof groups === 'object' && !Array.isArray(groups)) {
    for (const groupKey of ['brand', 'mpn', 'package', 'parameters', 'other']) {
      const groupComparisons = readPath(groups, `${groupKey}.comparisons`);
      if (Array.isArray(groupComparisons)) comparisons.push(...groupComparisons);
    }
  }
  const fallbackComparisons = readPath(evidence, 'comparisons');
  if (comparisons.length === 0 && Array.isArray(fallbackComparisons)) {
    comparisons.push(...fallbackComparisons);
  }
  return comparisons.map(normalizeComparisonRecord);
}

export function buildEvidenceSummary(
  candidate: any,
  locale: string,
  t: (key: string) => string,
): string[] {
  return collectEvidenceComparisons(candidate)
    .filter((comparison) => String(comparison.status || '') !== 'matched')
    .map((comparison) => {
      const field = humanizeComparisonField(comparison.label ?? comparison.key, locale, t);
      const status = String(comparison.status || '');
      const sourceValue = formatValue(comparison.sourceValue, '-');
      const candidateValue = formatValue(comparison.candidateValue, '-');
      if (status === 'mismatch') {
        return localized(
          locale,
          t,
          `${field}不一致（原始 ${sourceValue} / 候选 ${candidateValue}）`,
          `${field} differs (source ${sourceValue} / candidate ${candidateValue})`,
        );
      }
      if (status === 'missing_candidate') {
        return localized(locale, t, `${field}候选缺失`, `${field} missing on candidate`);
      }
      if (status === 'missing_source') {
        return localized(locale, t, `${field}原始缺失`, `${field} missing on source`);
      }
      if (status === 'missing_both' || status === 'missing') {
        return localized(locale, t, `${field}证据缺失`, `${field} evidence missing`);
      }
      return localized(
        locale,
        t,
        `${field}${comparisonStatusLabel(status, locale, t)}`,
        `${field} ${status}`,
      );
    });
}

export function EvidenceSummary({
  candidate,
  locale,
  t,
}: {
  candidate: any;
  locale: string;
  t: (key: string) => string;
}) {
  const issues = buildEvidenceSummary(candidate, locale, t);
  const visibleIssues = issues.slice(0, 4);
  const overflowCount = Math.max(issues.length - visibleIssues.length, 0);
  return (
    <div
      data-testid="review-drawer-evidence-summary"
      className="rounded-control border-status-amber bg-status-amber-bg text-status-amber border px-3 py-2 text-xs"
    >
      <span className="font-semibold">{localized(locale, t, '需复核：', 'Review: ')}</span>
      {visibleIssues.length > 0 ? (
        <>
          <span>{visibleIssues.join('；')}</span>
          {overflowCount > 0 && (
            <span>
              {localized(locale, t, `；另有 ${overflowCount} 项`, `; ${overflowCount} more`)}
            </span>
          )}
        </>
      ) : (
        <span>{localized(locale, t, '关键属性一致', 'Key attributes match')}</span>
      )}
    </div>
  );
}
