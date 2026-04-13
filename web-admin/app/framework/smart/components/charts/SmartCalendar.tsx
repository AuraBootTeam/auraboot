/**
 * SmartCalendar Component
 *
 * A calendar component using pure React + Tailwind CSS for visualizing
 * delivery dates, maintenance schedules, audit schedules, and other
 * date-based events.
 */

import React, { useMemo, useState, useCallback } from 'react';
import { useChartData } from '~/framework/smart/hooks/useChartData';
import type {
  ChartDataSource,
  DrillDownConfig,
  LinkageConfig,
  FilterConfig,
} from '~/framework/smart/types/chart';
import { cn } from '~/utils/cn';

/**
 * Props for SmartCalendar component
 */
export interface SmartCalendarProps {
  /** Chart title */
  title?: string;
  /** Data source configuration */
  dataSource: ChartDataSource;
  /** Field containing event date */
  dateField?: string;
  /** Field for event title */
  titleField?: string;
  /** Field for color-coding events */
  categoryField?: string;
  /** Default view mode */
  view?: 'month' | 'week';
  /** Drill-down configuration */
  drillDown?: DrillDownConfig;
  /** Linkage configuration */
  linkage?: LinkageConfig;
  /** Callback when drill-down is triggered */
  onDrillDown?: (filters: FilterConfig[]) => void;
  /** Callback when linkage filter is emitted */
  onLinkageEmit?: (filters: FilterConfig[]) => void;
  /** Linkage filters from other charts */
  linkageFilters?: FilterConfig[];
  /** Custom CSS class */
  className?: string;
  /** Custom inline styles */
  style?: React.CSSProperties;
  /** Auto-refresh interval in milliseconds (0 = disabled) */
  refreshInterval?: number;
}

// Color palette for category-based coloring
const CATEGORY_COLORS: Record<string, string> = {};
const PALETTE = [
  'bg-blue-500',
  'bg-green-500',
  'bg-yellow-500',
  'bg-red-500',
  'bg-purple-500',
  'bg-pink-500',
  'bg-indigo-500',
  'bg-teal-500',
  'bg-orange-500',
  'bg-cyan-500',
];

const PALETTE_TEXT = [
  'text-blue-700 bg-blue-50',
  'text-green-700 bg-green-50',
  'text-yellow-700 bg-yellow-50',
  'text-red-700 bg-red-50',
  'text-purple-700 bg-purple-50',
  'text-pink-700 bg-pink-50',
  'text-indigo-700 bg-indigo-50',
  'text-teal-700 bg-teal-50',
  'text-orange-700 bg-orange-50',
  'text-cyan-700 bg-cyan-50',
];

const WEEKDAY_LABELS = ['日', '一', '二', '三', '四', '五', '六'];

/**
 * Check if data source is configured enough to fetch data
 */
function isDataSourceConfigured(dataSource: ChartDataSource): boolean {
  if (!dataSource) return false;
  switch (dataSource.type) {
    case 'aggregate':
      return !!(dataSource.modelCode && dataSource.metrics?.length);
    case 'namedQuery':
      return !!dataSource.queryCode;
    case 'static':
      return true;
    default:
      return false;
  }
}

/**
 * Get the date key string (YYYY-MM-DD) from a Date
 */
function toDateKey(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

/**
 * Parse a value to a Date object, returns null on failure
 */
function parseDate(value: unknown): Date | null {
  if (!value) return null;
  const d = new Date(value as string | number);
  return isNaN(d.getTime()) ? null : d;
}

/**
 * Get the category color class by index
 */
function getCategoryDotColor(category: string, categories: string[]): string {
  if (!category) return PALETTE[0];
  const idx = categories.indexOf(category);
  return PALETTE[idx >= 0 ? idx % PALETTE.length : 0];
}

function getCategoryChipColor(category: string, categories: string[]): string {
  if (!category) return PALETTE_TEXT[0];
  const idx = categories.indexOf(category);
  return PALETTE_TEXT[idx >= 0 ? idx % PALETTE_TEXT.length : 0];
}

interface CalendarEvent {
  date: Date;
  dateKey: string;
  title: string;
  category: string;
  raw: Record<string, unknown>;
}

/**
 * SmartCalendar - A calendar component for date-based event visualization
 *
 * @example
 * // Basic calendar
 * <SmartCalendar
 *   title="交付日历"
 *   dataSource={{
 *     type: 'namedQuery',
 *     queryCode: 'delivery_schedule',
 *   }}
 *   dateField="delivery_date"
 *   titleField="order_name"
 *   categoryField="status"
 * />
 */
export const SmartCalendar: React.FC<SmartCalendarProps> = ({
  title,
  dataSource,
  dateField,
  titleField,
  categoryField,
  view = 'month',
  drillDown,
  linkage,
  onDrillDown,
  onLinkageEmit,
  linkageFilters,
  refreshInterval,
  className,
  style,
}) => {
  const isConfigured = isDataSourceConfigured(dataSource);

  const { data, loading, error } = useChartData({
    dataSource,
    linkageFilters: linkage?.receiveFilter ? linkageFilters : undefined,
    refreshInterval,
    enabled: isConfigured,
  });

  // Current displayed month
  const [currentDate, setCurrentDate] = useState(() => new Date());
  // Selected day for detail view
  const [selectedDate, setSelectedDate] = useState<string | null>(null);
  // Current view mode
  const [currentView, setCurrentView] = useState<'month' | 'week'>(view);

  const today = useMemo(() => toDateKey(new Date()), []);

  /**
   * Resolve field names from data meta
   */
  const resolvedFields = useMemo(() => {
    const dims = data?.meta?.dimensions || [];
    const mets = data?.meta?.metrics || [];
    const allFields = [...dims, ...mets];

    const resolveByHint = (hints: string[], fallback?: string) => {
      for (const hint of hints) {
        const found = allFields.find((f) => f.toLowerCase().includes(hint));
        if (found) return found;
      }
      return fallback;
    };

    return {
      date:
        dateField || resolveByHint(['date', 'time', 'scheduled', 'plan', 'due'], dims[0]) || 'date',
      title:
        titleField ||
        resolveByHint(['title', 'name', 'label', 'subject'], dims[1] || mets[0]) ||
        'title',
      category: categoryField || resolveByHint(['category', 'status', 'type', 'group'], undefined),
    };
  }, [data, dateField, titleField, categoryField]);

  /**
   * Parse events from data rows
   */
  const { events, eventsByDate, categories } = useMemo(() => {
    if (!data?.rows?.length) {
      return {
        events: [],
        eventsByDate: {} as Record<string, CalendarEvent[]>,
        categories: [] as string[],
      };
    }

    const { date, title: titleKey, category } = resolvedFields;
    const parsed: CalendarEvent[] = [];

    for (const row of data.rows) {
      const d = parseDate(row[date]);
      if (!d) continue;

      parsed.push({
        date: d,
        dateKey: toDateKey(d),
        title: String(row[titleKey] ?? ''),
        category: category ? String(row[category] ?? '') : '',
        raw: row,
      });
    }

    // Group by date key
    const grouped: Record<string, CalendarEvent[]> = {};
    for (const evt of parsed) {
      if (!grouped[evt.dateKey]) grouped[evt.dateKey] = [];
      grouped[evt.dateKey].push(evt);
    }

    // Unique categories
    const cats = [...new Set(parsed.map((e) => e.category).filter(Boolean))];

    return { events: parsed, eventsByDate: grouped, categories: cats };
  }, [data, resolvedFields]);

  /**
   * Build the calendar grid for the current month
   */
  const calendarGrid = useMemo(() => {
    const year = currentDate.getFullYear();
    const month = currentDate.getMonth();

    // First day of month
    const firstDay = new Date(year, month, 1);
    const startDow = firstDay.getDay(); // 0=Sunday

    // Last day of month
    const lastDay = new Date(year, month + 1, 0);
    const daysInMonth = lastDay.getDate();

    // Previous month days to fill
    const prevMonthLast = new Date(year, month, 0);
    const prevMonthDays = prevMonthLast.getDate();

    const cells: Array<{
      date: number;
      dateKey: string;
      isCurrentMonth: boolean;
      isToday: boolean;
      events: CalendarEvent[];
    }> = [];

    // Previous month trailing days
    for (let i = startDow - 1; i >= 0; i--) {
      const day = prevMonthDays - i;
      const d = new Date(year, month - 1, day);
      const key = toDateKey(d);
      cells.push({
        date: day,
        dateKey: key,
        isCurrentMonth: false,
        isToday: key === today,
        events: eventsByDate[key] || [],
      });
    }

    // Current month days
    for (let day = 1; day <= daysInMonth; day++) {
      const d = new Date(year, month, day);
      const key = toDateKey(d);
      cells.push({
        date: day,
        dateKey: key,
        isCurrentMonth: true,
        isToday: key === today,
        events: eventsByDate[key] || [],
      });
    }

    // Next month leading days to fill 6 rows
    const remaining = 42 - cells.length; // 6 rows * 7 cols
    for (let day = 1; day <= remaining; day++) {
      const d = new Date(year, month + 1, day);
      const key = toDateKey(d);
      cells.push({
        date: day,
        dateKey: key,
        isCurrentMonth: false,
        isToday: key === today,
        events: eventsByDate[key] || [],
      });
    }

    return cells;
  }, [currentDate, eventsByDate, today]);

  /**
   * Build the week view grid
   */
  const weekGrid = useMemo(() => {
    const d = new Date(currentDate);
    const dow = d.getDay();
    const weekStart = new Date(d);
    weekStart.setDate(d.getDate() - dow);

    const cells: Array<{
      date: number;
      dateKey: string;
      isCurrentMonth: boolean;
      isToday: boolean;
      events: CalendarEvent[];
      fullDate: Date;
    }> = [];

    for (let i = 0; i < 7; i++) {
      const day = new Date(weekStart);
      day.setDate(weekStart.getDate() + i);
      const key = toDateKey(day);
      cells.push({
        date: day.getDate(),
        dateKey: key,
        isCurrentMonth: day.getMonth() === currentDate.getMonth(),
        isToday: key === today,
        events: eventsByDate[key] || [],
        fullDate: day,
      });
    }

    return cells;
  }, [currentDate, eventsByDate, today]);

  /**
   * Navigation handlers
   */
  const goToPrevMonth = useCallback(() => {
    setCurrentDate((prev) => {
      const d = new Date(prev);
      d.setMonth(d.getMonth() - 1);
      return d;
    });
    setSelectedDate(null);
  }, []);

  const goToNextMonth = useCallback(() => {
    setCurrentDate((prev) => {
      const d = new Date(prev);
      d.setMonth(d.getMonth() + 1);
      return d;
    });
    setSelectedDate(null);
  }, []);

  const goToToday = useCallback(() => {
    setCurrentDate(new Date());
    setSelectedDate(today);
  }, [today]);

  const goToPrevWeek = useCallback(() => {
    setCurrentDate((prev) => {
      const d = new Date(prev);
      d.setDate(d.getDate() - 7);
      return d;
    });
    setSelectedDate(null);
  }, []);

  const goToNextWeek = useCallback(() => {
    setCurrentDate((prev) => {
      const d = new Date(prev);
      d.setDate(d.getDate() + 7);
      return d;
    });
    setSelectedDate(null);
  }, []);

  /**
   * Handle day click
   */
  const handleDayClick = useCallback(
    (dateKey: string) => {
      setSelectedDate((prev) => (prev === dateKey ? null : dateKey));

      // Drill-down / linkage on day click
      const dateFieldName = resolvedFields.date;
      const filter: FilterConfig = {
        field: dateFieldName,
        operator: 'eq',
        value: dateKey,
      };

      if (drillDown?.enabled && onDrillDown) {
        onDrillDown([filter]);
      }
      if (linkage?.enabled && linkage?.emitFilter && onLinkageEmit) {
        onLinkageEmit([filter]);
      }
    },
    [resolvedFields, drillDown, linkage, onDrillDown, onLinkageEmit],
  );

  /**
   * Format month label
   */
  const monthLabel = useMemo(() => {
    const year = currentDate.getFullYear();
    const month = currentDate.getMonth() + 1;
    return `${year}年${month}月`;
  }, [currentDate]);

  // Events for selected date
  const selectedEvents = useMemo(() => {
    if (!selectedDate) return [];
    return eventsByDate[selectedDate] || [];
  }, [selectedDate, eventsByDate]);

  // Not configured state
  if (!isConfigured) {
    return (
      <div
        className={cn(
          'flex items-center justify-center rounded-lg border border-dashed border-gray-300 bg-white p-4',
          className,
        )}
        style={{ minHeight: 0, ...style }}
      >
        <div className="text-center">
          <div className="mb-3 text-4xl text-gray-400">📆</div>
          <div className="font-medium text-gray-500">{title || '日历'}</div>
          <div className="mt-1 text-sm text-gray-400">请在右侧配置数据源</div>
        </div>
      </div>
    );
  }

  // Loading state
  if (loading) {
    return (
      <div
        className={cn(
          'flex items-center justify-center rounded-lg border border-gray-200 bg-white p-4',
          className,
        )}
        style={{ minHeight: 0, ...style }}
      >
        <div className="flex flex-col items-center gap-2">
          <div className="h-8 w-8 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
          <span className="text-sm text-gray-500">Loading...</span>
        </div>
      </div>
    );
  }

  // Error state
  if (error) {
    return (
      <div
        className={cn(
          'flex items-center justify-center rounded-lg border border-red-200 bg-white p-4',
          className,
        )}
        style={{ minHeight: 0, ...style }}
        role="alert"
      >
        <div className="text-center">
          <div className="mb-2 text-lg text-red-500">Failed to load calendar</div>
          <div className="text-sm text-gray-500">{error.message}</div>
        </div>
      </div>
    );
  }

  return (
    <div className={cn('rounded-lg border border-gray-200 bg-white p-4', className)} style={style}>
      {/* Header: title + navigation */}
      <div className="mb-4 flex items-center justify-between">
        <div className="flex items-center gap-2">
          {title && <h3 className="text-sm font-medium text-gray-700">{title}</h3>}
        </div>

        <div className="flex items-center gap-2">
          {/* View toggle */}
          <div className="flex items-center overflow-hidden rounded-md border text-xs">
            <button
              type="button"
              className={cn(
                'px-2 py-1 transition-colors',
                currentView === 'month'
                  ? 'bg-blue-500 text-white'
                  : 'bg-white text-gray-600 hover:bg-gray-50',
              )}
              onClick={() => setCurrentView('month')}
            >
              月
            </button>
            <button
              type="button"
              className={cn(
                'px-2 py-1 transition-colors',
                currentView === 'week'
                  ? 'bg-blue-500 text-white'
                  : 'bg-white text-gray-600 hover:bg-gray-50',
              )}
              onClick={() => setCurrentView('week')}
            >
              周
            </button>
          </div>

          {/* Navigation */}
          <button
            type="button"
            className="rounded p-1 text-gray-500 hover:bg-gray-100"
            onClick={currentView === 'month' ? goToPrevMonth : goToPrevWeek}
            aria-label="上一页"
          >
            <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M15 19l-7-7 7-7"
              />
            </svg>
          </button>

          <button
            type="button"
            className="min-w-[100px] rounded px-2 py-1 text-center text-sm font-medium text-gray-700 hover:bg-gray-50"
            onClick={goToToday}
            title="回到今天"
          >
            {monthLabel}
          </button>

          <button
            type="button"
            className="rounded p-1 text-gray-500 hover:bg-gray-100"
            onClick={currentView === 'month' ? goToNextMonth : goToNextWeek}
            aria-label="下一页"
          >
            <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
            </svg>
          </button>
        </div>
      </div>

      {/* Calendar grid */}
      <div className="flex gap-4">
        {/* Main calendar area */}
        <div className="min-w-0 flex-1">
          {/* Weekday headers */}
          <div className="mb-1 grid grid-cols-7">
            {WEEKDAY_LABELS.map((label) => (
              <div key={label} className="py-1 text-center text-xs font-medium text-gray-500">
                {label}
              </div>
            ))}
          </div>

          {/* Month view */}
          {currentView === 'month' && (
            <div className="grid grid-cols-7 gap-px overflow-hidden rounded-lg bg-gray-100">
              {calendarGrid.map((cell) => {
                const hasEvents = cell.events.length > 0;
                const isSelected = selectedDate === cell.dateKey;

                return (
                  <button
                    key={cell.dateKey}
                    type="button"
                    className={cn(
                      'relative min-h-[60px] bg-white p-1 text-left transition-colors',
                      'hover:bg-blue-50 focus:ring-1 focus:ring-blue-300 focus:outline-none focus:ring-inset',
                      !cell.isCurrentMonth && 'bg-gray-50',
                      isSelected && 'bg-blue-50 ring-2 ring-blue-400 ring-inset',
                    )}
                    onClick={() => handleDayClick(cell.dateKey)}
                  >
                    {/* Day number */}
                    <span
                      className={cn(
                        'inline-flex h-6 w-6 items-center justify-center rounded-full text-xs',
                        cell.isToday && 'bg-blue-500 font-bold text-white',
                        !cell.isToday && cell.isCurrentMonth && 'text-gray-700',
                        !cell.isToday && !cell.isCurrentMonth && 'text-gray-400',
                      )}
                    >
                      {cell.date}
                    </span>

                    {/* Event dots */}
                    {hasEvents && (
                      <div className="mt-0.5 flex flex-wrap gap-0.5">
                        {cell.events.slice(0, 3).map((evt, idx) => (
                          <span
                            key={idx}
                            className={cn(
                              'h-1.5 w-1.5 rounded-full',
                              getCategoryDotColor(evt.category, categories),
                            )}
                            title={evt.title}
                          />
                        ))}
                        {cell.events.length > 3 && (
                          <span className="text-[9px] leading-none text-gray-400">
                            +{cell.events.length - 3}
                          </span>
                        )}
                      </div>
                    )}
                  </button>
                );
              })}
            </div>
          )}

          {/* Week view */}
          {currentView === 'week' && (
            <div className="grid grid-cols-7 gap-px overflow-hidden rounded-lg bg-gray-100">
              {weekGrid.map((cell) => {
                const isSelected = selectedDate === cell.dateKey;

                return (
                  <button
                    key={cell.dateKey}
                    type="button"
                    className={cn(
                      'min-h-[200px] bg-white p-2 text-left transition-colors',
                      'hover:bg-blue-50 focus:ring-1 focus:ring-blue-300 focus:outline-none focus:ring-inset',
                      isSelected && 'bg-blue-50 ring-2 ring-blue-400 ring-inset',
                    )}
                    onClick={() => handleDayClick(cell.dateKey)}
                  >
                    {/* Day number */}
                    <span
                      className={cn(
                        'mb-1 inline-flex h-6 w-6 items-center justify-center rounded-full text-xs',
                        cell.isToday && 'bg-blue-500 font-bold text-white',
                        !cell.isToday && 'text-gray-700',
                      )}
                    >
                      {cell.date}
                    </span>

                    {/* Event chips */}
                    <div className="space-y-1">
                      {cell.events.slice(0, 5).map((evt, idx) => (
                        <div
                          key={idx}
                          className={cn(
                            'truncate rounded px-1 py-0.5 text-[10px]',
                            getCategoryChipColor(evt.category, categories),
                          )}
                          title={evt.title}
                        >
                          {evt.title}
                        </div>
                      ))}
                      {cell.events.length > 5 && (
                        <div className="text-[10px] text-gray-400">
                          +{cell.events.length - 5} 更多
                        </div>
                      )}
                    </div>
                  </button>
                );
              })}
            </div>
          )}
        </div>

        {/* Selected date detail panel */}
        {selectedDate && (
          <div className="w-56 flex-shrink-0 border-l pl-4">
            <div className="mb-2 flex items-center justify-between">
              <h4 className="text-sm font-medium text-gray-700">{selectedDate}</h4>
              <button
                type="button"
                className="text-gray-400 hover:text-gray-600"
                onClick={() => setSelectedDate(null)}
              >
                <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M6 18L18 6M6 6l12 12"
                  />
                </svg>
              </button>
            </div>

            {selectedEvents.length === 0 ? (
              <p className="text-xs text-gray-400">当天无事件</p>
            ) : (
              <div className="max-h-[400px] space-y-2 overflow-y-auto">
                {selectedEvents.map((evt, idx) => (
                  <div
                    key={idx}
                    className="rounded-md border border-gray-100 p-2 transition-colors hover:border-gray-200"
                  >
                    <div className="truncate text-xs font-medium text-gray-700" title={evt.title}>
                      {evt.title}
                    </div>
                    {evt.category && (
                      <span
                        className={cn(
                          'mt-1 inline-block rounded px-1.5 py-0.5 text-[10px]',
                          getCategoryChipColor(evt.category, categories),
                        )}
                      >
                        {evt.category}
                      </span>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </div>

      {/* Category legend */}
      {categories.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-3 border-t pt-3">
          {categories.map((cat) => (
            <div key={cat} className="flex items-center gap-1">
              <span
                className={cn('h-2.5 w-2.5 rounded-full', getCategoryDotColor(cat, categories))}
              />
              <span className="text-xs text-gray-600">{cat}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

export default SmartCalendar;
