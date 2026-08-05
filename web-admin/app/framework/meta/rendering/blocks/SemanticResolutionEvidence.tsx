import React from 'react';
import { getLocalizedText, type LocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { readPath } from './workbenchBlockUtils';

type SemanticResolutionEvidenceProps = {
  traces: any[];
  locale: string;
  t: (key: string) => string;
  title?: string | LocalizedText | null;
};

function parseJsonValue(value: unknown): unknown {
  let current = value;
  for (let depth = 0; depth < 4; depth += 1) {
    if (
      current &&
      typeof current === 'object' &&
      !Array.isArray(current) &&
      ['json', 'jsonb'].includes(
        String((current as Record<string, unknown>).type || '').toLowerCase(),
      ) &&
      Object.prototype.hasOwnProperty.call(current, 'value')
    ) {
      current = (current as Record<string, unknown>).value;
      continue;
    }
    if (typeof current !== 'string') return current;
    const trimmed = current.trim();
    if (!trimmed || (!trimmed.startsWith('{') && !trimmed.startsWith('['))) return current;
    try {
      current = JSON.parse(trimmed);
    } catch {
      return current;
    }
  }
  return current;
}

/** Collects typed semantic field traces from declaratively configured JSON evidence locations. */
export function collectSemanticResolutionTraces(record: unknown, groups: unknown): any[] {
  const parsedGroups = parseJsonValue(groups);
  if (!Array.isArray(parsedGroups)) return [];
  const traces: any[] = [];
  const identities = new Set<string>();
  parsedGroups.forEach((group: any) => {
    const source = group?.sourceField
      ? parseJsonValue(readPath(record, group.sourceField))
      : record;
    const items = readPath(source, group?.path);
    if (!Array.isArray(items)) return;
    items.forEach((item: any) => {
      const trace = group?.itemPath ? readPath(item, group.itemPath) : item;
      if (!trace || typeof trace !== 'object' || Array.isArray(trace)) return;
      const field = String(trace.standardField || '');
      if (!field) return;
      const selected = trace.selected && typeof trace.selected === 'object' ? trace.selected : {};
      const identity = [
        field,
        String(selected.ruleId || ''),
        String(selected.ruleVersion || ''),
        String(selected.normalizedValue || ''),
      ].join('|');
      if (identities.has(identity)) return;
      identities.add(identity);
      traces.push(trace);
    });
  });
  return traces;
}

function localized(locale: string, zh: string, en: string): string {
  return locale.toLowerCase().startsWith('zh') ? zh : en;
}

function formatValue(value: unknown, emptyText = '-'): string {
  if (value === undefined || value === null || value === '') return emptyText;
  if (Array.isArray(value) && value.length === 0) return emptyText;
  if (Array.isArray(value) && value.every((item) => typeof item !== 'object' || item === null)) {
    return value.map((item) => formatValue(item, emptyText)).join(', ');
  }
  if (typeof value === 'object') return JSON.stringify(value, null, 2);
  return String(value);
}

function resolutionStateLabel(state: unknown, locale: string): string {
  const labels: Record<string, [string, string]> = {
    resolved: ['已解析', 'Resolved'],
    ambiguous: ['多候选', 'Ambiguous'],
    conflict: ['来源冲突', 'Conflict'],
    unparsed: ['未解析', 'Unparsed'],
  };
  const label = labels[String(state || '').toLowerCase()] || ['未知', 'Unknown'];
  return localized(locale, label[0], label[1]);
}

const stateToneClass: Record<string, string> = {
  resolved: 'bg-emerald-50 text-emerald-700 border-emerald-200',
  conflict: 'bg-rose-50 text-rose-700 border-rose-200',
  ambiguous: 'bg-amber-50 text-amber-700 border-amber-200',
  unparsed: 'bg-amber-50 text-amber-700 border-amber-200',
};

export const SemanticResolutionEvidence: React.FC<SemanticResolutionEvidenceProps> = ({
  traces,
  locale,
  t,
  title,
}) => {
  if (traces.length === 0) return null;
  return (
    <section
      data-testid="review-drawer-semantic-resolutions"
      className="rounded-card border-border bg-panel border p-3"
    >
      <h3 className="text-text text-sm font-semibold">
        {getLocalizedText(
          title || {
            'zh-CN': '字段语义解析证据',
            en: 'Field semantic resolution evidence',
          },
          locale,
          t,
        )}
      </h3>
      <div className="mt-3 grid gap-3">
        {traces.map((resolution: any, index: number) => {
          const field = String(resolution.standardField || `field-${index + 1}`);
          const selected =
            resolution.selected && typeof resolution.selected === 'object'
              ? resolution.selected
              : {};
          const alternatives = Array.isArray(resolution.alternatives)
            ? resolution.alternatives
            : [];
          const vetoes = Array.isArray(resolution.vetoes) ? resolution.vetoes : [];
          const span =
            selected.sourceSpan && typeof selected.sourceSpan === 'object'
              ? selected.sourceSpan
              : {};
          const state = String(resolution.state || 'unparsed').toLowerCase();
          return (
            <article
              key={`${field}-${index}`}
              data-testid={`review-drawer-semantic-resolution-${field}`}
              className="rounded-control border-border bg-subtle border p-3"
            >
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="text-text text-sm font-semibold">{field}</span>
                <span
                  className={`rounded-pill border px-2 py-0.5 text-xs font-medium ${
                    stateToneClass[state] || stateToneClass.unparsed
                  }`}
                >
                  {resolutionStateLabel(state, locale)}
                </span>
              </div>
              <dl className="mt-3 grid gap-2 text-xs md:grid-cols-2">
                <div>
                  <dt className="text-text-2">{localized(locale, '采用值', 'Selected')}</dt>
                  <dd className="text-text mt-0.5 font-semibold [overflow-wrap:anywhere]">
                    {formatValue(selected.normalizedValue)}
                  </dd>
                </div>
                <div>
                  <dt className="text-text-2">{localized(locale, '来源', 'Source')}</dt>
                  <dd className="text-text mt-0.5 [overflow-wrap:anywhere]">
                    {formatValue(selected.sourceColumn)} · {formatValue(selected.sourceRole)}
                    {Number.isFinite(span.startOffset) && Number.isFinite(span.endOffset)
                      ? ` [${span.startOffset}, ${span.endOffset})`
                      : ''}
                  </dd>
                </div>
                <div>
                  <dt className="text-text-2">
                    {localized(locale, '规则 / 版本', 'Rule / Version')}
                  </dt>
                  <dd className="text-text mt-0.5 [overflow-wrap:anywhere]">
                    {formatValue(selected.ruleId)} @ {formatValue(selected.ruleVersion)}
                  </dd>
                </div>
                <div>
                  <dt className="text-text-2">{localized(locale, '其他候选', 'Alternatives')}</dt>
                  <dd className="text-text mt-0.5 [overflow-wrap:anywhere]">
                    {alternatives.length > 0
                      ? alternatives
                          .map((item: any) => formatValue(item?.normalizedValue))
                          .join(', ')
                      : '-'}
                  </dd>
                </div>
              </dl>
              {vetoes.length > 0 && (
                <div className="border-status-red bg-status-red-bg text-status-red rounded-control mt-3 border px-2.5 py-2 text-xs">
                  {localized(locale, '阻断原因', 'Vetoes')}: {vetoes.join(', ')}
                </div>
              )}
            </article>
          );
        })}
      </div>
    </section>
  );
};
