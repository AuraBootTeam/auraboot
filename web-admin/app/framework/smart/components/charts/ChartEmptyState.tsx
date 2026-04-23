import React from 'react';
import { cn } from '~/utils/cn';

type ChartEmptyVariant = 'bar' | 'line' | 'pie' | 'metric';

interface ChartEmptyStateProps {
  title?: string;
  description?: string;
  variant?: ChartEmptyVariant;
  className?: string;
}

function EmptyIllustration({ variant }: { variant: ChartEmptyVariant }) {
  if (variant === 'metric') {
    return (
      <div className="relative h-18 w-18">
        <div className="absolute inset-0 rounded-[24px] bg-[linear-gradient(135deg,_rgba(59,130,246,0.18),_rgba(56,189,248,0.06))]" />
        <div className="absolute inset-x-4 top-4 h-2 rounded-full bg-sky-200/80" />
        <div className="absolute inset-x-4 top-8 h-6 rounded-2xl bg-slate-200/80" />
        <div className="absolute bottom-4 left-4 h-2 w-8 rounded-full bg-slate-200/70" />
      </div>
    );
  }

  if (variant === 'line') {
    return (
      <div className="relative h-18 w-24 overflow-hidden rounded-[24px] bg-[linear-gradient(180deg,_rgba(14,165,233,0.08),_rgba(255,255,255,0.28))]">
        <svg viewBox="0 0 96 72" className="h-full w-full">
          <path
            d="M10 52 C24 48, 28 26, 42 28 S62 52, 76 38 S88 24, 90 30"
            fill="none"
            stroke="rgba(59,130,246,0.55)"
            strokeWidth="4"
            strokeLinecap="round"
          />
          <circle cx="24" cy="44" r="3" fill="rgba(14,165,233,0.75)" />
          <circle cx="42" cy="28" r="3" fill="rgba(14,165,233,0.75)" />
          <circle cx="76" cy="38" r="3" fill="rgba(14,165,233,0.75)" />
        </svg>
      </div>
    );
  }

  if (variant === 'pie') {
    return (
      <div className="relative flex h-18 w-18 items-center justify-center rounded-full bg-[conic-gradient(from_180deg,_rgba(59,130,246,0.16),_rgba(14,165,233,0.42),_rgba(148,163,184,0.12),_rgba(59,130,246,0.16))]">
        <div className="h-9 w-9 rounded-full bg-white/90 shadow-inner" />
      </div>
    );
  }

  return (
    <div className="flex h-18 w-24 items-end gap-2 rounded-[24px] bg-[linear-gradient(180deg,_rgba(59,130,246,0.06),_rgba(255,255,255,0.34))] px-4 py-3">
      <div className="h-8 w-4 rounded-t-xl bg-sky-200/90" />
      <div className="h-12 w-4 rounded-t-xl bg-blue-300/90" />
      <div className="h-6 w-4 rounded-t-xl bg-slate-200/90" />
      <div className="h-10 w-4 rounded-t-xl bg-cyan-300/90" />
    </div>
  );
}

export const ChartEmptyState: React.FC<ChartEmptyStateProps> = ({
  title,
  description = 'Data source is connected. Add records to populate this widget.',
  variant = 'bar',
  className,
}) => {
  return (
    <div
      className={cn(
        'flex h-full min-h-0 items-center justify-center rounded-[22px] border border-dashed border-slate-200/90',
        'bg-[radial-gradient(circle_at_top,_rgba(14,165,233,0.10),_transparent_38%),linear-gradient(180deg,_rgba(248,250,252,0.95),_rgba(255,255,255,0.98))]',
        'p-5 text-center',
        className,
      )}
      data-testid="chart-empty-state"
    >
      <div className="flex max-w-[280px] flex-col items-center">
        <EmptyIllustration variant={variant} />
        <div className="mt-4 inline-flex items-center rounded-full border border-sky-100 bg-white/80 px-2.5 py-1 text-[11px] font-semibold tracking-[0.14em] text-sky-700 uppercase">
          Awaiting Data
        </div>
        <div className="mt-3 text-sm font-semibold text-slate-900">{title || 'No data yet'}</div>
        <div className="mt-1 text-xs leading-5 text-slate-500">{description}</div>
      </div>
    </div>
  );
};

export default ChartEmptyState;
