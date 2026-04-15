/**
 * StatsCardWidget — Single standalone stat card with gradient background.
 *
 * Same visual as one cell in StatsRowWidget but usable independently.
 * Fetches from /api/workbench/stats?keys={statKey}.
 *
 * Props (passed via widget config):
 *   statKey: string     — which stat to display
 *   gradient?: string   — color theme (blue, amber, emerald, violet, etc.)
 *   linkTo?: string     — optional URL to navigate on click
 *
 * @since 6.5.0
 */

import React from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { useWorkbenchStats } from './useWorkbenchStats';
import type { StatItem } from './workbench-types';

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

interface StatsCardWidgetProps {
  statKey?: string;
  gradient?: string;
  linkTo?: string;
  className?: string;
}

export function StatsCardWidget({
  statKey = 'inbox_pending',
  gradient = 'blue',
  linkTo,
  className = '',
}: StatsCardWidgetProps) {
  const { t } = useI18n();
  const keys = [statKey];
  const { stats, loading } = useWorkbenchStats({ keys });
  const item = stats[statKey];
  const gradientClass = GRADIENT_MAP[gradient] ?? GRADIENT_MAP.blue;

  const handleClick = () => {
    if (linkTo) {
      window.location.href = linkTo;
    }
  };

  if (loading) {
    return (
      <div
        className={`relative overflow-hidden rounded-xl p-5 animate-pulse ${className}`}
        data-testid="stats-card-skeleton"
      >
        <div className={`absolute inset-0 ${gradientClass} opacity-40`} />
        <div className="relative space-y-3">
          <div className="h-3 w-20 rounded bg-white/30" />
          <div className="h-8 w-24 rounded bg-white/30" />
          <div className="h-2.5 w-28 rounded bg-white/20" />
        </div>
      </div>
    );
  }

  const label = item?.label
    ? t(`workbench.stats.${statKey}`, {}, item.label)
    : t(`workbench.stats.${statKey}`, {}, statKey);
  const trendText = item ? formatTrend(item) : null;

  return (
    <div
      className={`relative overflow-hidden rounded-xl p-5 text-white transition-transform hover:-translate-y-0.5 hover:shadow-lg ${gradientClass} ${linkTo ? 'cursor-pointer' : ''} ${className}`}
      onClick={handleClick}
      data-testid={`stats-card-${statKey}`}
    >
      {/* Decorative circle */}
      <div className="absolute -right-3 -top-3 h-16 w-16 rounded-full bg-white/10" />

      {/* Label */}
      <div className="text-xs opacity-85">{label}</div>

      {/* Value */}
      <div className="mt-2 text-3xl font-extrabold leading-none">
        {item ? formatValue(item) : '-'}
      </div>

      {/* Trend */}
      {trendText && <div className="mt-2 text-[11px] opacity-70">{trendText}</div>}
    </div>
  );
}
