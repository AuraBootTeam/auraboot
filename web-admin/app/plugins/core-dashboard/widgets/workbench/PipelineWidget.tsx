/**
 * PipelineWidget — Horizontal funnel/bar showing CRM opportunity stages.
 *
 * Data source: GET /api/workbench/pipeline
 * Returns: { stages: [{ code, label, count, amount, color }], totalAmount, totalCount }
 */

import React, { useEffect, useState } from 'react';
import { get } from '~/shared/services/http-client';
import { useI18n } from '~/contexts/I18nContext';

interface PipelineStage {
  code: string;
  label: string;
  count: number;
  amount: number;
  color: string;
}

interface PipelineData {
  stages: PipelineStage[];
  totalAmount: number;
  totalCount: number;
}

interface PipelineWidgetProps {
  title?: string;
  className?: string;
}

const DEFAULT_COLORS: Record<string, string> = {
  prospecting: '#3B82F6',
  qualification: '#8B5CF6',
  proposal: '#F59E0B',
  negotiation: '#EF4444',
  closed_won: '#10B981',
  closed_lost: '#6B7280',
};

function formatAmount(amount: number): string {
  if (amount >= 10000) {
    return `\u00a5${(amount / 10000).toFixed(amount % 10000 === 0 ? 0 : 1)}w`;
  }
  return `\u00a5${amount.toLocaleString()}`;
}

export function PipelineWidget({ title, className = '' }: PipelineWidgetProps) {
  const { t } = useI18n();
  const [data, setData] = useState<PipelineData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  const resolvedTitle = title || t('workbench.pipeline.title', {}, 'Sales Pipeline');

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      try {
        const result = await get<PipelineData>('/api/workbench/pipeline');
        if (!cancelled && result.code === '0' && result.data) {
          setData(result.data);
        } else if (!cancelled) {
          setError(true);
        }
      } catch {
        if (!cancelled) setError(true);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  const maxCount = data
    ? Math.max(...data.stages.map((s) => s.count), 1)
    : 1;

  const handleStageClick = (stageCode: string) => {
    const filters = JSON.stringify([
      { fieldName: 'crm_opp_stage', operator: 'eq', value: stageCode },
    ]);
    window.location.href = `/crm_opportunity?filters=${encodeURIComponent(filters)}`;
  };

  // --- Loading ---
  if (loading) {
    return (
      <div className={`flex h-full flex-col ${className}`} data-testid="pipeline-skeleton">
        <div className="mb-3 flex items-center justify-between px-1">
          <div className="h-4 w-28 animate-pulse rounded bg-gray-100" />
          <div className="h-3 w-20 animate-pulse rounded bg-gray-100" />
        </div>
        <div className="flex-1 space-y-3">
          {[1, 2, 3, 4].map((i) => (
            <div key={i} className="space-y-1.5">
              <div className="h-3 w-20 animate-pulse rounded bg-gray-100" />
              <div
                className="h-6 animate-pulse rounded bg-gray-100"
                style={{ width: `${90 - i * 15}%` }}
              />
            </div>
          ))}
        </div>
      </div>
    );
  }

  // --- Empty / Error ---
  if (error || !data || data.stages.length === 0) {
    return (
      <div className={`flex h-full flex-col ${className}`} data-testid="pipeline-empty">
        <div className="mb-3 flex items-center justify-between px-1">
          <span className="text-sm font-semibold text-gray-900">{resolvedTitle}</span>
        </div>
        <div className="flex flex-1 flex-col items-center justify-center text-gray-400">
          <span className="mb-1 text-2xl">{'\uD83D\uDCC8'}</span>
          <span className="text-sm">
            {t('workbench.pipeline.empty', {}, 'No opportunity data')}
          </span>
          <a
            href="/crm_opportunity"
            className="mt-2 text-xs text-blue-500 hover:text-blue-600"
          >
            {t('workbench.pipeline.goToCrm', {}, 'Go to CRM')} &rarr;
          </a>
        </div>
      </div>
    );
  }

  // --- Data ---
  return (
    <div className={`flex h-full flex-col ${className}`} data-testid="pipeline-widget">
      {/* Header */}
      <div className="mb-3 flex items-center justify-between px-1">
        <span className="text-sm font-semibold text-gray-900">{resolvedTitle}</span>
        <span className="text-xs text-gray-500">
          {t('workbench.pipeline.total', {}, 'Total')}: {formatAmount(data.totalAmount)}
        </span>
      </div>

      {/* Stage bars */}
      <div className="flex-1 space-y-2.5 overflow-y-auto">
        {data.stages.map((stage) => {
          const barWidth = Math.max((stage.count / maxCount) * 100, 8);
          const color = stage.color || DEFAULT_COLORS[stage.code] || '#6B7280';

          return (
            <button
              key={stage.code}
              type="button"
              onClick={() => handleStageClick(stage.code)}
              className="group block w-full cursor-pointer text-left"
            >
              <div className="mb-1 flex items-center justify-between">
                <span className="text-xs text-gray-600 group-hover:text-gray-900">
                  {stage.label}
                </span>
                <span className="text-[11px] text-gray-400">
                  {stage.count} &middot; {formatAmount(stage.amount)}
                </span>
              </div>
              <div className="h-5 w-full rounded-md bg-gray-50">
                <div
                  className="flex h-full items-center rounded-md px-2 text-[10px] font-medium text-white transition-all group-hover:opacity-90"
                  style={{
                    width: `${barWidth}%`,
                    backgroundColor: color,
                  }}
                >
                  {stage.count > 0 && stage.count}
                </div>
              </div>
            </button>
          );
        })}
      </div>
    </div>
  );
}
