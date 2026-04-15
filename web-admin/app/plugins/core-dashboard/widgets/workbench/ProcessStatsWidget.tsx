/**
 * ProcessStatsWidget — Mini dashboard showing 3 BPM metrics.
 *
 * Metrics:
 * 1. Completion Rate — circular progress ring (SVG) + percentage
 * 2. Avg Duration — large number + "hours" label
 * 3. Running — large number + "processes" label + completed this week
 *
 * Data source: GET /api/workbench/bpm-stats
 */

import React, { useEffect, useState, useCallback } from 'react';
import { get } from '~/shared/services/http-client';
import { useI18n } from '~/contexts/I18nContext';

interface ProcessStatsWidgetProps {
  title?: string;
  className?: string;
}

interface BpmStats {
  completionRate: number;
  avgDurationHours: number;
  overdueRate: number;
  runningCount: number;
  completedThisWeek: number;
  completedLastWeek: number;
}

function getRingColor(rate: number): string {
  if (rate >= 80) return '#22c55e'; // green-500
  if (rate >= 50) return '#f59e0b'; // amber-500
  return '#ef4444'; // red-500
}

function ProgressRing({ rate, color }: { rate: number; color: string }) {
  const normalizedRate = Math.min(Math.max(rate, 0), 100) / 100;
  const circumference = 2 * Math.PI * 28; // r=28, circumference ~175.9

  return (
    <svg width="64" height="64" viewBox="0 0 64 64" className="mx-auto">
      <circle cx="32" cy="32" r="28" fill="none" stroke="#e5e7eb" strokeWidth="4" />
      <circle
        cx="32"
        cy="32"
        r="28"
        fill="none"
        stroke={color}
        strokeWidth="4"
        strokeDasharray={`${normalizedRate * circumference} ${circumference}`}
        strokeLinecap="round"
        transform="rotate(-90 32 32)"
        className="transition-all duration-700"
      />
      <text
        x="32"
        y="32"
        textAnchor="middle"
        dominantBaseline="central"
        className="fill-gray-900 text-sm font-semibold"
        fontSize="14"
      >
        {Math.round(rate)}%
      </text>
    </svg>
  );
}

export function ProcessStatsWidget({
  title,
  className = '',
}: ProcessStatsWidgetProps) {
  const { t } = useI18n();
  const [stats, setStats] = useState<BpmStats | null>(null);
  const [loading, setLoading] = useState(true);

  const resolvedTitle = title || t('workbench.processStats.title', {}, 'Process Stats');

  const loadStats = useCallback(async () => {
    setLoading(true);
    try {
      const result = await get<BpmStats>('/api/workbench/bpm-stats');
      if (result.code === '0' && result.data) {
        setStats(result.data);
      }
    } catch {
      setStats(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadStats();
  }, [loadStats]);

  // --- Header ---
  const renderHeader = () => (
    <div className="mb-4">
      <span className="text-sm font-semibold text-gray-900">{resolvedTitle}</span>
    </div>
  );

  // --- Skeleton loading ---
  if (loading) {
    return (
      <div className={`flex h-full flex-col ${className}`}>
        {renderHeader()}
        <div className="flex flex-1 items-center gap-3">
          {Array.from({ length: 3 }).map((_, i) => (
            <div key={i} className="flex flex-1 flex-col items-center p-3">
              <div className="mb-2 h-16 w-16 animate-pulse rounded-full bg-gray-200" />
              <div className="h-3 w-16 animate-pulse rounded bg-gray-200" />
            </div>
          ))}
        </div>
      </div>
    );
  }

  // --- No data ---
  if (!stats) {
    return (
      <div className={`flex h-full flex-col ${className}`}>
        {renderHeader()}
        <div className="flex flex-1 flex-col items-center justify-center text-gray-400">
          <span className="text-sm">
            {t('workbench.processStats.noData', {}, 'No data available')}
          </span>
        </div>
      </div>
    );
  }

  const ringColor = getRingColor(stats.completionRate);

  return (
    <div className={`flex h-full flex-col ${className}`}>
      {renderHeader()}
      <div className="flex flex-1 items-start gap-3">
        {/* Completion Rate */}
        <div className="flex flex-1 flex-col items-center p-3 text-center">
          <ProgressRing rate={stats.completionRate} color={ringColor} />
          <span className="mt-2 text-[11px] text-gray-500">
            {t('workbench.processStats.completionRate', {}, 'Completion Rate')}
          </span>
        </div>

        {/* Avg Duration */}
        <div className="flex flex-1 flex-col items-center p-3 text-center">
          <div className="flex h-16 items-center justify-center">
            <span className="text-2xl font-bold text-gray-900">
              {stats.avgDurationHours.toFixed(1)}
            </span>
          </div>
          <span className="mt-1 text-[10px] text-gray-400">
            {t('workbench.processStats.hours', {}, 'hours')}
          </span>
          <span className="mt-0.5 text-[11px] text-gray-500">
            {t('workbench.processStats.avgDuration', {}, 'Avg Duration')}
          </span>
        </div>

        {/* Running */}
        <div className="flex flex-1 flex-col items-center p-3 text-center">
          <div className="flex h-16 items-center justify-center">
            <span className="text-2xl font-bold text-gray-900">
              {stats.runningCount}
            </span>
          </div>
          <span className="mt-1 text-[10px] text-gray-400">
            {t('workbench.processStats.processes', {}, 'processes')}
          </span>
          <span className="mt-0.5 text-[11px] text-gray-500">
            {t('workbench.processStats.running', {}, 'Running')}
          </span>
          <span className="mt-1 text-[10px] text-gray-400">
            {stats.completedThisWeek} {t('workbench.processStats.completedThisWeek', {}, 'completed this week')}
          </span>
        </div>
      </div>
    </div>
  );
}
