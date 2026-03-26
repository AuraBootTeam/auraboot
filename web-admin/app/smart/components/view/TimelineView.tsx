/**
 * TimelineView — Resource timeline visualization (GAP-128)
 *
 * Displays records as horizontal bars on a time axis, grouped by a resource field.
 * Unlike Gantt (which shows task dependencies), Timeline focuses on parallel
 * resource allocation (scheduling, room booking, equipment usage).
 *
 * ViewConfig fields:
 * - timelineStartField: start date/datetime field
 * - timelineEndField: end date/datetime field
 * - timelineResourceField: field to group rows by (resource)
 * - timelineTitleField: label shown on bars (defaults to "name")
 */

import React, { useState, useEffect, useMemo } from 'react';
import { fetchResult } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import type { ViewConfig } from '~/smart/types/savedView';
import type { FilterConfig } from '~/smart/types/chart';
import { DataLimitBanner, ViewDiagnostics, ViewEmptyState } from './shared';

interface TimelineViewProps {
  viewConfig?: ViewConfig;
  modelCode: string;
  onItemClick?: (recordId: string) => void;
  onOpenViewConfig?: () => void;
  onSwitchToTableView?: () => void;
  linkageFilters?: FilterConfig[];
  className?: string;
}

interface TimelineItem {
  id: string;
  title: string;
  resource: string;
  start: Date;
  end: Date;
  record: Record<string, unknown>;
}

const DAY_MS = 86400000;

export const TimelineView: React.FC<TimelineViewProps> = ({
  viewConfig,
  modelCode,
  onItemClick,
  onOpenViewConfig,
  onSwitchToTableView,
  className,
}) => {
  const [data, setData] = useState<Record<string, unknown>[]>([]);
  const [loading, setLoading] = useState(true);
  const [totalCount, setTotalCount] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);

  const startField = viewConfig?.timelineStartField;
  const endField = viewConfig?.timelineEndField;
  const resourceField = viewConfig?.timelineResourceField;
  const titleField = viewConfig?.timelineTitleField || 'name';

  // Fetch data
  useEffect(() => {
    if (!startField || !endField || !modelCode) {
      setLoading(false);
      return;
    }
    const slug = modelCode.replace(/_/g, '-');
    setLoading(true);
    fetchResult<any>(`/api/dynamic/${slug}/list?pageNum=1&pageSize=200`)
      .then((result) => {
        if (ResultHelper.isSuccess(result) && result.data?.records) {
          setData(result.data.records);
          setTotalCount(result.data.total ?? result.data.records.length);
        }
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [modelCode, startField, endField, refreshKey]);

  const handleRefresh = () => setRefreshKey((k) => k + 1);

  // Parse items and compute diagnostics
  const { items, resources, dateRange, diagnostics, issueRecords } = useMemo(() => {
    const parsedItems: TimelineItem[] = [];
    const resourceSet = new Set<string>();
    let minDate = Infinity;
    let maxDate = -Infinity;

    let missingStart = 0, missingEnd = 0, missingBoth = 0, invalidDate = 0, valid = 0;
    const issues: Array<{ recordId: string; title: string; reason: string; details: Record<string, unknown> }> = [];

    for (const record of data) {
      const startVal = record[startField || ''];
      const endVal = record[endField || ''];
      const recordId = String(record.pid || record.id || '');
      const titleVal = String(record[titleField] || recordId);
      const hasStart = startVal != null && startVal !== '';
      const hasEnd = endVal != null && endVal !== '';

      if (!hasStart && !hasEnd) {
        missingBoth++;
        if (issues.length < 10) issues.push({ recordId, title: titleVal, reason: 'missing_both', details: { startField: startField || '', endField: endField || '', startValue: startVal, endValue: endVal } });
        continue;
      }
      if (!hasStart) {
        missingStart++;
        if (issues.length < 10) issues.push({ recordId, title: titleVal, reason: 'missing_start', details: { startField: startField || '', startValue: startVal } });
        continue;
      }
      if (!hasEnd) {
        missingEnd++;
        if (issues.length < 10) issues.push({ recordId, title: titleVal, reason: 'missing_end', details: { endField: endField || '', endValue: endVal } });
        continue;
      }

      const start = new Date(String(startVal));
      const end = new Date(String(endVal));
      if (isNaN(start.getTime()) || isNaN(end.getTime())) {
        invalidDate++;
        if (issues.length < 10) issues.push({ recordId, title: titleVal, reason: 'invalid_date', details: { startValue: startVal, endValue: endVal } });
        continue;
      }

      valid++;
      const resource = resourceField ? String(record[resourceField] || '(Unassigned)') : '(All)';
      resourceSet.add(resource);

      if (start.getTime() < minDate) minDate = start.getTime();
      if (end.getTime() > maxDate) maxDate = end.getTime();

      parsedItems.push({
        id: recordId,
        title: String(record[titleField] || ''),
        resource,
        start,
        end,
        record,
      });
    }

    // Add 1-day padding
    if (minDate === Infinity) {
      const now = Date.now();
      minDate = now - 7 * DAY_MS;
      maxDate = now + 7 * DAY_MS;
    }
    minDate -= DAY_MS;
    maxDate += DAY_MS;

    return {
      items: parsedItems,
      resources: Array.from(resourceSet).sort(),
      dateRange: { start: new Date(minDate), end: new Date(maxDate) },
      diagnostics: { totalRecords: data.length, validRecords: valid, missingStart, missingEnd, missingBoth, invalidDate },
      issueRecords: issues,
    };
  }, [data, startField, endField, resourceField, titleField]);

  // Not configured
  if (!startField || !endField) {
    return (
      <ViewEmptyState
        variant="not-configured"
        title="Timeline view not configured"
        description="Set start date, end date, and resource fields."
        onConfigure={onOpenViewConfig}
        onSwitchToTableView={onSwitchToTableView}
        className={className}
      />
    );
  }

  if (loading) {
    return <div className="flex items-center justify-center p-12"><div className="h-5 w-5 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" /></div>;
  }

  // Render timeline
  const totalDays = Math.max(Math.ceil((dateRange.end.getTime() - dateRange.start.getTime()) / DAY_MS), 1);
  const dayWidth = 40; // px per day
  const rowHeight = 40;
  const headerHeight = 50;

  const formatDate = (d: Date) => `${d.getMonth() + 1}/${d.getDate()}`;

  // Generate day headers
  const dayHeaders: Array<{ date: Date; x: number }> = [];
  for (let i = 0; i < totalDays; i++) {
    dayHeaders.push({
      date: new Date(dateRange.start.getTime() + i * DAY_MS),
      x: i * dayWidth,
    });
  }

  return (
    <div className={`overflow-auto ${className || ''}`} data-testid="timeline-view">
      <DataLimitBanner
        fetchedCount={data.length}
        totalCount={totalCount}
        onSwitchToTableView={onSwitchToTableView}
        className="mx-4 mt-3"
      />
      <div style={{ minWidth: totalDays * dayWidth + 200, position: 'relative' }}>
        {/* Header row — dates */}
        <div className="sticky top-0 z-10 flex border-b border-gray-200 bg-gray-50" style={{ height: headerHeight }}>
          <div className="flex-shrink-0 border-r border-gray-200 bg-gray-50 px-3 py-2 text-xs font-medium text-gray-500" style={{ width: 200 }}>
            {resourceField ? 'Resource' : 'Items'}
          </div>
          <div className="relative flex-1">
            {dayHeaders.map((dh, i) => (
              <div
                key={i}
                className="absolute top-0 border-r border-gray-100 px-1 py-2 text-center text-xs text-gray-400"
                style={{ left: dh.x, width: dayWidth, height: headerHeight }}
              >
                {formatDate(dh.date)}
              </div>
            ))}
          </div>
        </div>

        {/* Resource rows */}
        {(resources.length > 0 ? resources : ['(All)']).map((resource, ri) => {
          const resourceItems = items.filter((item) => item.resource === resource);
          return (
            <div key={resource} className="flex border-b border-gray-100" style={{ height: rowHeight }}>
              {/* Resource label */}
              <div className="flex-shrink-0 border-r border-gray-200 px-3 py-2 text-xs font-medium text-gray-700 truncate" style={{ width: 200 }}>
                {resource}
              </div>
              {/* Timeline bars */}
              <div className="relative flex-1">
                {resourceItems.map((item) => {
                  const startOffset = (item.start.getTime() - dateRange.start.getTime()) / DAY_MS * dayWidth;
                  const duration = Math.max((item.end.getTime() - item.start.getTime()) / DAY_MS * dayWidth, dayWidth / 2);
                  const colors = ['bg-blue-200 text-blue-800', 'bg-green-200 text-green-800', 'bg-purple-200 text-purple-800', 'bg-amber-200 text-amber-800'];
                  const color = colors[ri % colors.length];
                  return (
                    <div
                      key={item.id}
                      className={`absolute top-1 rounded px-1.5 text-xs font-medium truncate cursor-pointer hover:opacity-80 ${color}`}
                      style={{ left: startOffset, width: duration, height: rowHeight - 8, lineHeight: `${rowHeight - 8}px` }}
                      title={`${item.title} (${item.start.toLocaleDateString()} - ${item.end.toLocaleDateString()})`}
                      onClick={() => onItemClick?.(item.id)}
                      data-testid={`timeline-item-${item.id}`}
                    >
                      {item.title}
                    </div>
                  );
                })}
              </div>
            </div>
          );
        })}

        {items.length === 0 && diagnostics.totalRecords > 0 && (
          <ViewDiagnostics
            totalRecords={diagnostics.totalRecords}
            validRecords={diagnostics.validRecords}
            categories={[
              { key: 'missing_both', label: 'Missing both dates', count: diagnostics.missingBoth },
              { key: 'missing_start', label: 'Missing start date', count: diagnostics.missingStart },
              { key: 'missing_end', label: 'Missing end date', count: diagnostics.missingEnd },
              { key: 'invalid_date', label: 'Invalid date value', count: diagnostics.invalidDate },
            ]}
            issues={issueRecords}
            fieldMapping={{ 'Start Field': startField!, 'End Field': endField! }}
            onRecordClick={onItemClick}
            onOpenViewConfig={onOpenViewConfig}
            onSwitchToTableView={onSwitchToTableView}
            onRefresh={handleRefresh}
          />
        )}
        {items.length === 0 && diagnostics.totalRecords === 0 && (
          <ViewEmptyState
            variant="no-data"
            title="No records found"
            description="Records with valid start and end dates will appear here."
          />
        )}
      </div>
    </div>
  );
};

export default TimelineView;
