/**
 * CalendarView Component
 *
 * Adapter that bridges SavedView's ViewConfig to FullCalendar.
 * Reads calendar configuration from ViewConfig and renders the FullCalendar component.
 */

import React, { useMemo, useCallback, useState, useEffect, useRef } from 'react';
import FullCalendar from '@fullcalendar/react';
import dayGridPlugin from '@fullcalendar/daygrid';
import timeGridPlugin from '@fullcalendar/timegrid';
import interactionPlugin from '@fullcalendar/interaction';
import listPlugin from '@fullcalendar/list';
import type { EventClickArg, EventDropArg, DateSelectArg } from '@fullcalendar/core';
import type { ViewConfig } from '~/framework/smart/types/savedView';
import type { FilterConfig } from '~/framework/smart/types/chart';
import { dynamicService } from '~/services/dynamicService';
import { cn } from '~/utils/cn';
import { DataLimitBanner, ViewDiagnostics, ViewEmptyState } from './shared';

/**
 * Props for CalendarView component
 */
export interface CalendarViewProps {
  /** View configuration containing calendar settings */
  viewConfig?: ViewConfig;
  /** Model code for data fetching */
  modelCode: string;
  /** Callback when an event (record) is clicked */
  onEventClick?: (recordId: string) => void;
  /** Callback when an event is moved (date changed via drag) */
  onEventMove?: (recordId: string, newStart: string, newEnd: string | null) => void;
  /** External filter conditions */
  linkageFilters?: FilterConfig[];
  /** Callback to open view configuration */
  onOpenViewConfig?: () => void;
  /** Callback to switch to table view */
  onSwitchToTableView?: () => void;
  /** Custom CSS class */
  className?: string;
}

interface CalendarEvent {
  id: string;
  title: string;
  start: string;
  end?: string;
  backgroundColor?: string;
  borderColor?: string;
  extendedProps?: Record<string, unknown>;
}

/**
 * CalendarView - Bridges SavedView ViewConfig to FullCalendar
 *
 * Converts the calendar fields from ViewConfig into FullCalendar events.
 */
export const CalendarView: React.FC<CalendarViewProps> = ({
  viewConfig,
  modelCode,
  onEventClick,
  onEventMove,
  onOpenViewConfig,
  onSwitchToTableView,
  linkageFilters,
  className,
}) => {
  const [events, setEvents] = useState<CalendarEvent[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [totalCount, setTotalCount] = useState(0);
  const [diagnostics, setDiagnostics] = useState({
    totalRecords: 0,
    validRecords: 0,
    missingDate: 0,
    invalidDate: 0,
  });
  const [issueRecords, setIssueRecords] = useState<
    Array<{ recordId: string; title: string; reason: string; details: Record<string, unknown> }>
  >([]);
  const abortRef = useRef<AbortController | null>(null);

  const dateField = viewConfig?.calendarDateField;
  const titleField = viewConfig?.calendarTitleField || 'name';
  const endDateField = viewConfig?.calendarEndDateField;
  const colorField = viewConfig?.calendarColorField;
  const defaultView = viewConfig?.calendarDefaultView || 'dayGridMonth';

  // Color palette for color field values
  const colorPalette = useMemo(
    () => [
      '#3B82F6',
      '#10B981',
      '#F59E0B',
      '#EF4444',
      '#8B5CF6',
      '#EC4899',
      '#06B6D4',
      '#84CC16',
      '#F97316',
      '#6366F1',
    ],
    [],
  );

  const colorMap = useRef<Record<string, string>>({});

  const getColorForValue = useCallback(
    (value: unknown): string => {
      const key = String(value ?? '');
      if (!colorMap.current[key]) {
        const index = Object.keys(colorMap.current).length % colorPalette.length;
        colorMap.current[key] = colorPalette[index];
      }
      return colorMap.current[key];
    },
    [colorPalette],
  );

  // Fetch records and convert to calendar events
  const fetchEvents = useCallback(async () => {
    if (!dateField) return;

    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    setLoading(true);
    setError(null);

    try {
      const result = await dynamicService.findByPage(modelCode, {
        page: 0,
        size: 500, // Fetch enough records for calendar display
      });

      if (controller.signal.aborted) return;

      setTotalCount(result.total ?? result.records.length);

      // Diagnostics tracking
      const issues: typeof issueRecords = [];
      let missingDate = 0,
        invalidDate = 0,
        valid = 0;

      const calendarEvents: CalendarEvent[] = [];
      for (const record of result.records) {
        const dateVal = record[dateField];
        const recordPid = String(record.pid ?? record.id ?? '');
        const titleVal = String(record[titleField] ?? record['name'] ?? recordPid);

        if (!dateVal) {
          missingDate++;
          if (issues.length < 10)
            issues.push({
              recordId: recordPid,
              title: titleVal,
              reason: 'missing_date',
              details: { dateField, dateValue: dateVal },
            });
          continue;
        }
        if (isNaN(new Date(String(dateVal)).getTime())) {
          invalidDate++;
          if (issues.length < 10)
            issues.push({
              recordId: recordPid,
              title: titleVal,
              reason: 'invalid_date',
              details: { dateField, dateValue: dateVal },
            });
          continue;
        }

        valid++;
        const startVal = dateVal as string;
        const endVal = endDateField ? (record[endDateField] as string) : undefined;
        const color = colorField ? getColorForValue(record[colorField]) : '#3B82F6';

        calendarEvents.push({
          id: String(record.id ?? record.pid ?? ''),
          title: titleVal,
          start: startVal,
          end: endVal || undefined,
          backgroundColor: color,
          borderColor: color,
          extendedProps: record,
        });
      }

      setDiagnostics({
        totalRecords: result.records.length,
        validRecords: valid,
        missingDate,
        invalidDate,
      });
      setIssueRecords(issues);
      setEvents(calendarEvents);
    } catch (err) {
      if (controller.signal.aborted) return;
      setError(err instanceof Error ? err.message : 'Failed to fetch calendar data');
    } finally {
      if (!controller.signal.aborted) {
        setLoading(false);
      }
    }
  }, [
    modelCode,
    dateField,
    titleField,
    endDateField,
    colorField,
    linkageFilters,
    getColorForValue,
  ]);

  useEffect(() => {
    fetchEvents();
    return () => {
      abortRef.current?.abort();
    };
  }, [fetchEvents]);

  // Handle event click
  const handleEventClick = useCallback(
    (info: EventClickArg) => {
      const eventRecord = info.event.extendedProps as Record<string, unknown>;
      const recordId = String(eventRecord.pid ?? eventRecord.id ?? info.event.id ?? '');
      if (recordId) {
        onEventClick?.(recordId);
      }
    },
    [onEventClick],
  );

  // Handle event drag (date change)
  const handleEventDrop = useCallback(
    async (info: EventDropArg) => {
      const recordId = info.event.id;
      const newStart = info.event.startStr;
      const newEnd = info.event.endStr || null;

      if (!dateField || !recordId) {
        info.revert();
        return;
      }

      try {
        const updateData: Record<string, unknown> = {
          [dateField]: newStart,
        };
        if (endDateField && newEnd) {
          updateData[endDateField] = newEnd;
        }

        await dynamicService.update(modelCode, recordId, updateData);
        onEventMove?.(recordId, newStart, newEnd);
      } catch {
        info.revert();
      }
    },
    [modelCode, dateField, endDateField, onEventMove],
  );

  // Handle date select (for future: create new record)
  const handleDateSelect = useCallback((_info: DateSelectArg) => {
    // Reserved for future: create new record on date select
  }, []);

  // No date field configured
  if (!dateField) {
    return (
      <ViewEmptyState
        variant="not-configured"
        title="Calendar not configured"
        description="Please configure the Date Field to display the calendar view."
        onConfigure={onOpenViewConfig}
        onSwitchToTableView={onSwitchToTableView}
        className={className}
      />
    );
  }

  if (error) {
    return (
      <ViewEmptyState
        variant="error"
        title="Failed to load calendar data"
        error={error}
        onRetry={fetchEvents}
        className={className}
      />
    );
  }

  return (
    <div className={cn('rounded-lg border border-gray-200 bg-white p-4', className)}>
      {loading && (
        <div className="mb-2 flex items-center justify-center py-2">
          <span className="mr-2 h-4 w-4 animate-spin rounded-full border-2 border-blue-200 border-t-blue-600" />
          <span className="text-sm text-gray-500">Loading...</span>
        </div>
      )}
      <DataLimitBanner
        fetchedCount={events.length}
        totalCount={totalCount}
        onSwitchToTableView={onSwitchToTableView}
        className="mb-3"
      />
      {events.length === 0 && diagnostics.totalRecords > 0 && !loading && (
        <ViewDiagnostics
          totalRecords={diagnostics.totalRecords}
          validRecords={diagnostics.validRecords}
          categories={[
            { key: 'missing_date', label: 'Missing date value', count: diagnostics.missingDate },
            { key: 'invalid_date', label: 'Invalid date format', count: diagnostics.invalidDate },
          ]}
          issues={issueRecords}
          fieldMapping={{ 'Date Field': dateField }}
          onOpenViewConfig={onOpenViewConfig}
          onSwitchToTableView={onSwitchToTableView}
          onRefresh={fetchEvents}
        />
      )}
      <FullCalendar
        plugins={[dayGridPlugin, timeGridPlugin, interactionPlugin, listPlugin]}
        initialView={defaultView}
        headerToolbar={{
          left: 'prev,next today',
          center: 'title',
          right: 'dayGridMonth,timeGridWeek,listWeek',
        }}
        events={events}
        editable={true}
        selectable={true}
        selectMirror={true}
        dayMaxEvents={true}
        eventClick={handleEventClick}
        eventDrop={handleEventDrop}
        select={handleDateSelect}
        height="auto"
        locale="zh-cn"
        buttonText={{
          today: 'Today',
          month: 'Month',
          week: 'Week',
          list: 'List',
        }}
        eventDisplay="block"
        eventTimeFormat={{
          hour: '2-digit',
          minute: '2-digit',
          meridiem: false,
        }}
      />
    </div>
  );
};

export default CalendarView;
