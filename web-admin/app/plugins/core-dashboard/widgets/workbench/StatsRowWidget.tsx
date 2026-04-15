/**
 * StatsRowWidget — Renders a row of colored gradient stat cards
 * for the workbench home page.
 */

import React, { useMemo } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { useWorkbenchStats } from './useWorkbenchStats';
import type { StatsConfig, StatItem } from './workbench-types';

const GRADIENT_MAP: Record<string, string> = {
  blue: 'bg-gradient-to-br from-blue-500 to-blue-700',
  amber: 'bg-gradient-to-br from-amber-500 to-amber-700',
  emerald: 'bg-gradient-to-br from-emerald-500 to-emerald-700',
  violet: 'bg-gradient-to-br from-violet-500 to-violet-700',
  rose: 'bg-gradient-to-br from-rose-500 to-rose-700',
  cyan: 'bg-gradient-to-br from-cyan-500 to-cyan-700',
  indigo: 'bg-gradient-to-br from-indigo-500 to-indigo-700',
  orange: 'bg-gradient-to-br from-orange-500 to-orange-700',
};

const TREND_ARROWS: Record<string, string> = {
  up: '\u2191',
  down: '\u2193',
  flat: '\u2192',
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
    if (num >= 10000) {
      return `\u00a5${(num / 10000).toFixed(num % 10000 === 0 ? 0 : 1)}\u4e07`;
    }
    return `\u00a5${num.toLocaleString()}`;
  }

  if (item.format === 'percent') {
    return `${item.value}%`;
  }

  // Default number format
  if (typeof raw === 'number' && !isNaN(raw)) {
    return raw.toLocaleString();
  }
  return String(item.value);
}

function formatTrend(item: StatItem): string | null {
  if (!item.trend) return null;
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

  if (loading) {
    return (
      <div className={`flex gap-4 ${className}`} data-testid="stats-row-skeleton">
        {configs.map((cfg) => (
          <div
            key={cfg.key}
            className="relative flex-1 overflow-hidden rounded-xl p-4 animate-pulse"
          >
            <div className={`absolute inset-0 ${GRADIENT_MAP[cfg.gradient] ?? GRADIENT_MAP.blue} opacity-40`} />
            <div className="relative space-y-2">
              <div className="h-3 w-16 rounded bg-white/30" />
              <div className="h-7 w-20 rounded bg-white/30" />
              <div className="h-2.5 w-24 rounded bg-white/20" />
            </div>
          </div>
        ))}
      </div>
    );
  }

  return (
    <div className={`flex gap-4 ${className}`} data-testid="stats-row">
      {configs.map((cfg) => {
        const item = stats[cfg.key];
        const gradientClass = GRADIENT_MAP[cfg.gradient] ?? GRADIENT_MAP.blue;
        const trendText = item ? formatTrend(item) : null;

        return (
          <div
            key={cfg.key}
            className={`relative flex-1 overflow-hidden rounded-xl p-4 text-white transition-transform hover:-translate-y-0.5 hover:shadow-lg ${gradientClass}`}
            data-testid={`stat-card-${cfg.key}`}
          >
            {/* Decorative circle */}
            <div className="absolute -right-2 -top-2 h-14 w-14 rounded-full bg-white/10" />

            {/* Label */}
            <div className="text-xs opacity-85">{t(cfg.title)}</div>

            {/* Value */}
            <div className="mt-1 text-2xl font-extrabold leading-none">
              {item ? formatValue(item) : '-'}
            </div>

            {/* Trend */}
            {trendText && (
              <div className="mt-1.5 text-[10px] opacity-70">{trendText}</div>
            )}
          </div>
        );
      })}
    </div>
  );
}
