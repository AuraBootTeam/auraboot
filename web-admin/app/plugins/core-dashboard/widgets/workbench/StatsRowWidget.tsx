/**
 * StatsRowWidget — Renders a row of neutral stat cards for the workbench.
 *
 * Visual contract (2026-05 redesign): white surfaces in a 4-column grid; each
 * card delegates to the same internal renderer as StatsCardWidget for parity.
 */

import React, { useMemo } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { useWorkbenchStats } from './useWorkbenchStats';
import { Sparkline } from './Sparkline';
import type { StatsConfig, StatItem } from './workbench-types';

const TREND_ARROWS: Record<string, string> = { up: '↑', down: '↓', flat: '—' };
const TREND_COLOR: Record<string, string> = {
  up: 'text-emerald-700',
  down: 'text-red-700',
  flat: 'text-gray-500',
};

const DEFAULT_STATS: StatsConfig[] = [
  { key: 'inbox_pending', title: 'workbench.stats.inbox_pending', gradient: 'blue' },
  { key: 'crm_opportunity_amount', title: 'workbench.stats.crm_opportunity_amount', gradient: 'amber' },
  { key: 'bpm_running', title: 'workbench.stats.bpm_running', gradient: 'emerald' },
  { key: 'crm_account_active', title: 'workbench.stats.crm_account_active', gradient: 'violet' },
];

function formatValue(item: StatItem): string {
  const raw = typeof item.value === 'string' ? parseFloat(item.value) : item.value;
  if (item.format === 'currency') {
    if (isNaN(raw as number)) return String(item.value);
    const num = raw as number;
    if (num >= 10000) return `¥${(num / 10000).toFixed(num % 10000 === 0 ? 0 : 1)}万`;
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

interface StatsRowWidgetProps {
  stats?: StatsConfig[];
  className?: string;
}

export function StatsRowWidget({ stats: statConfigs, className = '' }: StatsRowWidgetProps) {
  const configs = statConfigs ?? DEFAULT_STATS;
  const keys = useMemo(() => configs.map((c) => c.key), [configs]);
  const { stats, loading } = useWorkbenchStats({ keys });
  const { t } = useI18n();

  return (
    <div
      className={`grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4 ${className}`}
      data-testid="stats-row"
    >
      {configs.map((cfg) => {
        const item = stats[cfg.key];
        return (
          <div
            key={cfg.key}
            data-testid={`stat-card-${cfg.key}`}
            className="flex flex-col gap-3 rounded-[10px] bg-white border border-[#e3e8ee] p-5 min-h-[128px] dark:bg-gray-900 dark:border-gray-700"
          >
            <div className="text-[11px] font-medium uppercase tracking-wide text-gray-500 dark:text-gray-400">
              {t(cfg.title)}
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
          </div>
        );
      })}
    </div>
  );
}
