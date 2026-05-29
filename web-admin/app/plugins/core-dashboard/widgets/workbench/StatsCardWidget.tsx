/**
 * StatsCardWidget — Single neutral stat card with optional sparkline.
 *
 * Visual contract (2026-05 redesign):
 *   - White surface with 1px border, no gradient background.
 *   - Label (uppercase, 11px) above large value.
 *   - Sparkline + trend text in the footer row.
 *
 * The `gradient` prop is accepted for backward compatibility with existing
 * Dashboard JSON but no longer has any visual effect.
 *
 * @since 6.5.0  introduced
 * @since 6.6.0  redesigned (gradient removed, sparkline added)
 */

import React from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { useWorkbenchStats } from './useWorkbenchStats';
import { Sparkline } from './Sparkline';
import type { StatItem } from './workbench-types';

const TREND_ARROWS: Record<string, string> = {
  up: '↑',
  down: '↓',
  flat: '—',
};

const TREND_COLOR: Record<string, string> = {
  up: 'text-emerald-700',
  down: 'text-red-700',
  flat: 'text-gray-500',
};

function formatValue(item: StatItem): string {
  const raw = typeof item.value === 'string' ? parseFloat(item.value) : item.value;
  if (item.format === 'currency') {
    if (isNaN(raw as number)) return String(item.value);
    const num = raw as number;
    if (num >= 10000) {
      return `¥${(num / 10000).toFixed(num % 10000 === 0 ? 0 : 1)}万`;
    }
    return `¥${num.toLocaleString()}`;
  }
  if (item.format === 'percent') return `${item.value}%`;
  if (typeof raw === 'number' && !isNaN(raw)) return raw.toLocaleString();
  return String(item.value);
}

function formatTrend(item: StatItem): string {
  if (!item.trend) return '— no change';
  const arrow = TREND_ARROWS[item.trend.direction] ?? '';
  const suffix = item.trend.unit === 'percent' ? '%' : '';
  const periodLabel = item.trend.period === 'week' ? 'vs last week' : 'vs last month';
  return `${arrow} ${item.trend.value}${suffix} ${periodLabel}`;
}

interface StatsCardWidgetProps {
  statKey?: string;
  /** @deprecated kept for dashboard JSON compatibility; has no visual effect since 6.6.0 */
  gradient?: string;
  linkTo?: string;
  className?: string;
}

export function StatsCardWidget({
  statKey = 'inbox_pending',
  linkTo,
  className = '',
}: StatsCardWidgetProps) {
  const { t } = useI18n();
  const { stats, loading } = useWorkbenchStats({ keys: [statKey] });
  const item = stats[statKey];

  const cardBase =
    'flex flex-col gap-3 rounded-[10px] bg-white border border-[#e3e8ee] p-5 min-h-[128px] ' +
    'dark:bg-gray-900 dark:border-gray-700';

  const inner = (
    <>
      <div className="text-[11px] font-medium uppercase tracking-wide text-gray-500 dark:text-gray-400">
        {item ? t(item.label) : t(`workbench.stats.${statKey}`)}
      </div>
      <div className="text-[28px] leading-none font-semibold text-gray-900 dark:text-gray-100">
        {loading ? '—' : item ? formatValue(item) : '—'}
      </div>
      <div className="mt-auto flex items-center justify-between">
        <span className={`text-[12px] ${TREND_COLOR[item?.trend?.direction ?? 'flat']}`}>
          {item ? formatTrend(item) : ''}
        </span>
        <Sparkline points={item?.series?.points ?? []} />
      </div>
    </>
  );

  const testId = `stat-card-${statKey}`;
  if (linkTo) {
    return (
      <a
        href={linkTo}
        data-testid={testId}
        className={`${cardBase} hover:border-[#cdd5df] transition-colors ${className}`}
      >
        {inner}
      </a>
    );
  }
  return (
    <div data-testid={testId} className={`${cardBase} ${className}`}>
      {inner}
    </div>
  );
}
