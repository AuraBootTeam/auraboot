/**
 * SmartCountdown Component
 *
 * A countdown timer widget for dashboards.
 * Counts down to a target date and displays remaining time.
 */

import React, { useState, useEffect, useMemo } from 'react';
import { cn } from '~/utils/cn';

/**
 * Custom labels for time units
 */
interface CountdownLabels {
  days?: string;
  hours?: string;
  minutes?: string;
  seconds?: string;
}

/**
 * Props for SmartCountdown component
 */
export interface SmartCountdownProps {
  /** Widget title */
  title?: string;
  /** Target date in ISO format */
  targetDate?: string;
  /** Display format */
  format?: 'days' | 'full';
  /** Custom labels for time units */
  labels?: CountdownLabels;
  /** Custom CSS class */
  className?: string;
  /** Custom inline styles */
  style?: React.CSSProperties;
}

/**
 * Default labels
 */
const defaultLabels: Required<CountdownLabels> = {
  days: '天',
  hours: '时',
  minutes: '分',
  seconds: '秒',
};

/**
 * Calculate remaining time
 */
function calculateRemaining(targetDate: string): {
  days: number;
  hours: number;
  minutes: number;
  seconds: number;
  expired: boolean;
} {
  const now = new Date().getTime();
  const target = new Date(targetDate).getTime();
  const diff = target - now;

  if (diff <= 0) {
    return { days: 0, hours: 0, minutes: 0, seconds: 0, expired: true };
  }

  return {
    days: Math.floor(diff / (1000 * 60 * 60 * 24)),
    hours: Math.floor((diff % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60)),
    minutes: Math.floor((diff % (1000 * 60 * 60)) / (1000 * 60)),
    seconds: Math.floor((diff % (1000 * 60)) / 1000),
    expired: false,
  };
}

/**
 * Single time unit display
 */
const TimeUnit: React.FC<{ value: number; label: string }> = ({ value, label }) => (
  <div className="mx-2 flex flex-col items-center">
    <div className="min-w-[3rem] rounded-lg bg-gray-50 px-3 py-2 text-center text-3xl font-bold text-gray-900 tabular-nums">
      {String(value).padStart(2, '0')}
    </div>
    <div className="mt-1 text-xs text-gray-500">{label}</div>
  </div>
);

/**
 * Separator between time units
 */
const TimeSeparator: React.FC = () => (
  <div className="mt-2 self-start text-2xl font-bold text-gray-300">:</div>
);

export const SmartCountdown: React.FC<SmartCountdownProps> = ({
  title,
  targetDate,
  format = 'full',
  labels: customLabels,
  className,
  style,
}) => {
  const labels = useMemo(() => ({ ...defaultLabels, ...customLabels }), [customLabels]);

  const [remaining, setRemaining] = useState(() =>
    targetDate ? calculateRemaining(targetDate) : null,
  );

  useEffect(() => {
    if (!targetDate) {
      setRemaining(null);
      return;
    }

    // Calculate immediately
    setRemaining(calculateRemaining(targetDate));

    // Update every second
    const timer = setInterval(() => {
      const result = calculateRemaining(targetDate);
      setRemaining(result);
      if (result.expired) {
        clearInterval(timer);
      }
    }, 1000);

    return () => clearInterval(timer);
  }, [targetDate]);

  // No target date state
  if (!targetDate) {
    return (
      <div
        className={cn(
          'flex h-full items-center justify-center rounded-lg border border-dashed border-gray-300 bg-white p-4',
          className,
        )}
        style={style}
      >
        <div className="text-center">
          <div className="mb-3 text-4xl text-gray-400">⏰</div>
          <div className="font-medium text-gray-500">{title || '倒计时'}</div>
          <div className="mt-1 text-sm text-gray-400">请在右侧配置目标日期</div>
        </div>
      </div>
    );
  }

  // Expired state
  if (remaining?.expired) {
    return (
      <div
        className={cn(
          'flex h-full flex-col items-center justify-center rounded-lg border border-gray-200 bg-white p-4',
          className,
        )}
        style={style}
      >
        {title && <div className="mb-3 text-sm font-medium text-gray-500">{title}</div>}
        <div className="text-2xl font-bold text-green-600">已到达</div>
        <div className="mt-2 text-sm text-gray-400">
          {new Date(targetDate).toLocaleDateString('zh-CN')}
        </div>
      </div>
    );
  }

  return (
    <div
      className={cn(
        'flex h-full flex-col items-center justify-center rounded-lg border border-gray-200 bg-white p-4',
        className,
      )}
      style={style}
    >
      {title && <div className="mb-4 text-sm font-medium text-gray-500">{title}</div>}
      <div className="flex items-center justify-center">
        <TimeUnit value={remaining?.days ?? 0} label={labels.days} />
        {format === 'full' && (
          <>
            <TimeSeparator />
            <TimeUnit value={remaining?.hours ?? 0} label={labels.hours} />
            <TimeSeparator />
            <TimeUnit value={remaining?.minutes ?? 0} label={labels.minutes} />
            <TimeSeparator />
            <TimeUnit value={remaining?.seconds ?? 0} label={labels.seconds} />
          </>
        )}
      </div>
      <div className="mt-3 text-xs text-gray-400">
        目标: {new Date(targetDate).toLocaleDateString('zh-CN')}
      </div>
    </div>
  );
};

export default SmartCountdown;
