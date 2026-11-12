import React from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { readPath, resolveRuntimeValue, useRuntimeStateSubscription } from './workbenchBlockUtils';

export interface PrerequisiteTableBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

type Localized = string | Record<string, string>;

type PrerequisiteRule = {
  /** Record path that must resolve non-empty (optionally equal to `equals`). */
  when: string;
  /** When set, the rule matches only if the value equals it. */
  equals?: string;
  tone?: 'green' | 'amber' | 'blue' | 'gray' | 'red';
  label?: Localized;
};

type PrerequisiteRow = {
  label: Localized;
  /** First matching rule wins; without a match the fallback renders. */
  status?: PrerequisiteRule[];
  fallback?: PrerequisiteRule;
};

const RULE_PILL_CLASS: Record<NonNullable<PrerequisiteRule['tone']>, string> = {
  green: 'border-status-green bg-status-green-bg text-status-green',
  amber: 'border-status-amber bg-status-amber-bg text-status-amber',
  blue: 'border-status-blue bg-status-blue-bg text-status-blue',
  gray: 'border-status-gray bg-status-gray-bg text-status-gray',
  red: 'border-status-red bg-status-red-bg text-status-red',
};

const DEFAULT_RULE_LABEL: Record<NonNullable<PrerequisiteRule['tone']>, Localized> = {
  green: { 'zh-CN': '就绪', en: 'Ready' },
  amber: { 'zh-CN': '待定', en: 'Pending' },
  blue: { 'zh-CN': '已解析', en: 'Resolved' },
  gray: { 'zh-CN': '声明级', en: 'Declared' },
  red: { 'zh-CN': '缺失', en: 'Missing' },
};

function ruleMatches(rule: PrerequisiteRule, record: Record<string, unknown>): boolean {
  const value = readPath(record, rule.when);
  if (value === null || value === undefined || value === '') return false;
  return rule.equals === undefined || String(value) === rule.equals;
}

function resolveRule(
  row: PrerequisiteRow,
  record: Record<string, unknown>,
): PrerequisiteRule {
  const matched = (row.status ?? []).find((rule) => ruleMatches(rule, record));
  return matched ?? row.fallback ?? { when: '', tone: 'gray' };
}

/**
 * Declared prerequisite inputs with first-match status rules, evaluated against the
 * detail record (e.g. a release plan's 编译前置: source references, component mapping,
 * target slot, checks). Purely config-derived — rows state what the plan declares, not
 * an engine verdict; a verified verdict belongs to a query-backed block.
 */
export const PrerequisiteTableBlockRenderer: React.FC<PrerequisiteTableBlockRendererProps> = ({
  block,
  runtime,
}) => {
  useRuntimeStateSubscription(runtime);
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);
  const record =
    (block as any).record ??
    ((block as any).context !== undefined
      ? resolveRuntimeValue(runtime, (block as any).context)
      : context.record) ??
    {};
  const rows = Array.isArray((block as any).rows) ? ((block as any).rows as PrerequisiteRow[]) : [];
  const columns = (block as any).columns as [Localized, Localized] | undefined;
  const headerInput = columns?.[0] || { 'zh-CN': '输入', en: 'Input' };
  const headerStatus = columns?.[1] || { 'zh-CN': '当前状态', en: 'Status' };

  if (rows.length === 0) return null;

  return (
    <section
      className="rounded-card border-border bg-panel border px-4 py-4 shadow-sm sm:px-5"
      data-testid={`prerequisite-table-${block.id || 'block'}`}
    >
      {block.title ? (
        <h3 className="text-text mb-3 text-sm font-semibold">{getLocalizedText(block.title, locale, t)}</h3>
      ) : null}
      <div className="overflow-x-auto">
        <table className="w-full border-collapse text-sm">
          <thead>
            <tr className="border-border border-b">
              <th className="text-text-2 px-2 py-2 text-left text-xs font-semibold">
                {getLocalizedText(headerInput, locale, t)}
              </th>
              <th className="text-text-2 px-2 py-2 text-left text-xs font-semibold">
                {getLocalizedText(headerStatus, locale, t)}
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row, index) => {
              const rule = resolveRule(row, record);
              const tone = rule.tone || 'gray';
              return (
                <tr key={index} className="border-border/60 border-b last:border-b-0">
                  <td className="text-text px-2 py-2.5 align-middle">{getLocalizedText(row.label, locale, t)}</td>
                  <td className="px-2 py-2.5 align-middle" data-testid={`prerequisite-status-${index}`}>
                    <span
                      className={`rounded-pill inline-flex items-center border px-2.5 py-0.5 text-xs font-semibold ${RULE_PILL_CLASS[tone]}`}
                    >
                      {getLocalizedText(rule.label || DEFAULT_RULE_LABEL[tone], locale, t)}
                    </span>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
};

export default PrerequisiteTableBlockRenderer;
