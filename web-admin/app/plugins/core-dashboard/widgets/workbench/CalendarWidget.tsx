/**
 * CalendarWidget — Mini monthly calendar showing dates with pending inbox items.
 *
 * Features:
 * - Month grid (7 cols x 5-6 rows) using plain JS Date math
 * - Fetches inbox items to highlight dates with pending work
 * - Red dot for urgent, blue dot for normal pending items
 * - Today: blue circle background
 * - Prev/Next month navigation
 * - Pure CSS — no external calendar library
 *
 * @since 6.5.0
 */

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { get } from '~/shared/services/http-client';
import { useI18n } from '~/contexts/I18nContext';

interface InboxItem {
  createdAt: string;
  priority: string;
}

interface CalendarWidgetProps {
  className?: string;
}

const WEEKDAY_KEYS = [
  'workbench.calendar.sun',
  'workbench.calendar.mon',
  'workbench.calendar.tue',
  'workbench.calendar.wed',
  'workbench.calendar.thu',
  'workbench.calendar.fri',
  'workbench.calendar.sat',
];

const WEEKDAY_DEFAULTS = ['Su', 'Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa'];

interface DayCell {
  date: number;
  month: number; // 0-indexed
  year: number;
  isCurrentMonth: boolean;
  isToday: boolean;
  dotColor: 'red' | 'blue' | null;
}

function buildMonthGrid(year: number, month: number, dotMap: Map<string, 'red' | 'blue'>): DayCell[] {
  const today = new Date();
  const firstDay = new Date(year, month, 1);
  const startDow = firstDay.getDay(); // 0=Sun
  const daysInMonth = new Date(year, month + 1, 0).getDate();

  const cells: DayCell[] = [];

  // Fill leading days from previous month
  const prevMonthDays = new Date(year, month, 0).getDate();
  for (let i = startDow - 1; i >= 0; i--) {
    const d = prevMonthDays - i;
    const m = month - 1 < 0 ? 11 : month - 1;
    const y = month - 1 < 0 ? year - 1 : year;
    cells.push({
      date: d,
      month: m,
      year: y,
      isCurrentMonth: false,
      isToday: false,
      dotColor: null,
    });
  }

  // Current month days
  for (let d = 1; d <= daysInMonth; d++) {
    const key = `${year}-${String(month + 1).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
    const isToday =
      today.getFullYear() === year && today.getMonth() === month && today.getDate() === d;
    cells.push({
      date: d,
      month,
      year,
      isCurrentMonth: true,
      isToday,
      dotColor: dotMap.get(key) ?? null,
    });
  }

  // Fill trailing days
  const remaining = 7 - (cells.length % 7);
  if (remaining < 7) {
    const nextMonth = month + 1 > 11 ? 0 : month + 1;
    const nextYear = month + 1 > 11 ? year + 1 : year;
    for (let d = 1; d <= remaining; d++) {
      cells.push({
        date: d,
        month: nextMonth,
        year: nextYear,
        isCurrentMonth: false,
        isToday: false,
        dotColor: null,
      });
    }
  }

  return cells;
}

export function CalendarWidget({ className = '' }: CalendarWidgetProps) {
  const { t } = useI18n();
  const [viewDate, setViewDate] = useState(() => new Date());
  const [dotMap, setDotMap] = useState<Map<string, 'red' | 'blue'>>(new Map());
  const [loading, setLoading] = useState(true);

  const year = viewDate.getFullYear();
  const month = viewDate.getMonth();

  const fetchInboxDots = useCallback(async () => {
    setLoading(true);
    const result = await get<{ records: InboxItem[] }>('/api/inbox', {
      status: 'pending',
      pageNum: '1',
      pageSize: '50',
    });

    const map = new Map<string, 'red' | 'blue'>();
    if (result.code === '0' && result.data?.records) {
      for (const item of result.data.records) {
        const d = new Date(item.createdAt);
        const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
        const isUrgent = item.priority === 'urgent' || item.priority === 'high';
        // Red takes precedence over blue
        if (isUrgent || !map.has(key)) {
          map.set(key, isUrgent ? 'red' : 'blue');
        }
      }
    }
    setDotMap(map);
    setLoading(false);
  }, []);

  useEffect(() => {
    fetchInboxDots();
  }, [fetchInboxDots]);

  const cells = useMemo(() => buildMonthGrid(year, month, dotMap), [year, month, dotMap]);

  const handlePrev = () => {
    setViewDate(new Date(year, month - 1, 1));
  };

  const handleNext = () => {
    setViewDate(new Date(year, month + 1, 1));
  };

  const handleToday = () => {
    setViewDate(new Date());
  };

  const monthLabel = viewDate.toLocaleDateString(undefined, { year: 'numeric', month: 'long' });

  return (
    <div className={`flex h-full flex-col ${className}`} data-testid="calendar-widget">
      {/* Header */}
      <div className="mb-2 flex items-center justify-between">
        <span className="text-sm font-semibold text-gray-900">
          {t('workbench.calendar.title', {}, 'Calendar')}
        </span>
        <div className="flex items-center gap-1">
          <button
            type="button"
            onClick={handlePrev}
            className="flex h-6 w-6 cursor-pointer items-center justify-center rounded text-gray-400 hover:bg-gray-100 hover:text-gray-600"
            aria-label="Previous month"
          >
            &lsaquo;
          </button>
          <button
            type="button"
            onClick={handleToday}
            className="cursor-pointer rounded px-2 py-0.5 text-[11px] text-gray-500 hover:bg-gray-100"
          >
            {t('workbench.calendar.today', {}, 'Today')}
          </button>
          <button
            type="button"
            onClick={handleNext}
            className="flex h-6 w-6 cursor-pointer items-center justify-center rounded text-gray-400 hover:bg-gray-100 hover:text-gray-600"
            aria-label="Next month"
          >
            &rsaquo;
          </button>
        </div>
      </div>

      {/* Month label */}
      <div className="mb-2 text-center text-xs font-medium text-gray-600">{monthLabel}</div>

      {/* Weekday headers */}
      <div className="mb-1 grid grid-cols-7 gap-0">
        {WEEKDAY_KEYS.map((key, i) => (
          <div key={key} className="py-1 text-center text-[10px] font-medium text-gray-400">
            {t(key, {}, WEEKDAY_DEFAULTS[i])}
          </div>
        ))}
      </div>

      {/* Day grid */}
      {loading ? (
        <div className="flex flex-1 items-center justify-center">
          <div className="h-5 w-5 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
        </div>
      ) : (
        <div className="grid flex-1 grid-cols-7 gap-0">
          {cells.map((cell, i) => (
            <div
              key={i}
              className={`relative flex flex-col items-center justify-center py-1 ${
                cell.isCurrentMonth ? '' : 'opacity-30'
              }`}
            >
              <span
                className={`flex h-7 w-7 items-center justify-center rounded-full text-xs ${
                  cell.isToday
                    ? 'bg-blue-500 font-semibold text-white'
                    : cell.isCurrentMonth
                      ? 'text-gray-700'
                      : 'text-gray-300'
                }`}
              >
                {cell.date}
              </span>
              {cell.dotColor && (
                <span
                  className={`absolute bottom-0 h-1 w-1 rounded-full ${
                    cell.dotColor === 'red' ? 'bg-red-500' : 'bg-blue-400'
                  }`}
                />
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
