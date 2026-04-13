/**
 * GanttView Component
 *
 * Adapter that bridges SavedView's ViewConfig to gantt-task-react.
 * Reads gantt configuration from ViewConfig and renders the Gantt chart.
 */

import React, { useMemo, useCallback, useState, useEffect, useRef } from 'react';
import { Gantt, ViewMode, type Task as GanttTask } from 'gantt-task-react';
import 'gantt-task-react/dist/index.css';
import type { ViewConfig } from '~/framework/smart/types/savedView';
import type { FilterConfig } from '~/framework/smart/types/chart';
import { dynamicService } from '~/services/dynamicService';
import { fetchResult } from '~/services/http-client/HttpClient';
import { DataLimitBanner, ViewDiagnostics, ViewEmptyState } from './shared';
import { cn } from '~/utils/cn';

/**
 * Props for GanttView component
 */
export interface GanttViewProps {
  /** View configuration containing gantt settings */
  viewConfig?: ViewConfig;
  /** Model code for data fetching */
  modelCode: string;
  /** Callback when a task (record) is clicked */
  onTaskClick?: (recordId: string) => void;
  /** Callback when a task date is changed via drag */
  onTaskDateChange?: (recordId: string, start: string, end: string) => void;
  /** Callback when task progress is changed */
  onTaskProgressChange?: (recordId: string, progress: number) => void;
  /** Callback to open current view configuration */
  onOpenViewConfig?: () => void;
  /** Callback to switch back to table view */
  onSwitchToTableView?: () => void;
  /** External filter conditions */
  linkageFilters?: FilterConfig[];
  /** Custom CSS class */
  className?: string;
}

type GanttViewMode = 'Day' | 'Week' | 'Month';

const VIEW_MODE_MAP: Record<GanttViewMode, ViewMode> = {
  Day: ViewMode.Day,
  Week: ViewMode.Week,
  Month: ViewMode.Month,
};

interface GanttDataDiagnostics {
  totalRecords: number;
  validRecords: number;
  missingStartOnly: number;
  missingEndOnly: number;
  missingBoth: number;
  invalidDateRecords: number;
}

type GanttIssueReason = 'missing_both' | 'missing_start' | 'missing_end' | 'invalid_date';

interface GanttIssueRecord {
  recordId: string;
  title: string;
  reason: GanttIssueReason;
  startValue: unknown;
  endValue: unknown;
}

/**
 * GanttView - Bridges SavedView ViewConfig to gantt-task-react
 */
export const GanttView: React.FC<GanttViewProps> = ({
  viewConfig,
  modelCode,
  onTaskClick,
  onTaskDateChange,
  onTaskProgressChange,
  onOpenViewConfig,
  onSwitchToTableView,
  linkageFilters,
  className,
}) => {
  const [tasks, setTasks] = useState<GanttTask[]>([]);
  const [taskRecordPidMap, setTaskRecordPidMap] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [criticalPathIds, setCriticalPathIds] = useState<Set<string>>(new Set());
  const [diagnostics, setDiagnostics] = useState<GanttDataDiagnostics>({
    totalRecords: 0,
    validRecords: 0,
    missingStartOnly: 0,
    missingEndOnly: 0,
    missingBoth: 0,
    invalidDateRecords: 0,
  });
  const [issueRecords, setIssueRecords] = useState<GanttIssueRecord[]>([]);
  const [apiTotal, setApiTotal] = useState(0);
  const [viewMode, setViewMode] = useState<GanttViewMode>(
    (viewConfig?.ganttDefaultView as GanttViewMode) || 'Day',
  );
  const abortRef = useRef<AbortController | null>(null);

  const startDateField = viewConfig?.ganttStartDateField;
  const endDateField = viewConfig?.ganttEndDateField;
  const titleField = viewConfig?.ganttTitleField || 'name';
  const progressField = viewConfig?.ganttProgressField;
  const dependencyField = viewConfig?.ganttDependencyField;

  // Fetch records and convert to Gantt tasks
  const fetchTasks = useCallback(async () => {
    if (!startDateField || !endDateField) return;

    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    setLoading(true);
    setError(null);

    try {
      const result = await dynamicService.findByPage(modelCode, {
        page: 0,
        size: 500,
      });

      if (controller.signal.aborted) return;

      setApiTotal(result.total ?? result.records.length);

      const nextDiagnostics: GanttDataDiagnostics = {
        totalRecords: result.records.length,
        validRecords: 0,
        missingStartOnly: 0,
        missingEndOnly: 0,
        missingBoth: 0,
        invalidDateRecords: 0,
      };
      const nextIssues: GanttIssueRecord[] = [];

      const ganttTasks: GanttTask[] = [];
      const nextTaskRecordPidMap: Record<string, string> = {};
      for (const record of result.records) {
        const startRaw = record[startDateField];
        const endRaw = record[endDateField];
        const hasStart = startRaw != null && startRaw !== '';
        const hasEnd = endRaw != null && endRaw !== '';
        const titleVal = String(
          record[titleField] ?? record['name'] ?? record['pid'] ?? 'Untitled',
        );
        const recordPid = String(record.pid ?? record.id ?? '');
        const internalRecordId = String(record.id ?? record.pid ?? '');
        nextTaskRecordPidMap[internalRecordId] = recordPid;

        if (!hasStart && !hasEnd) {
          nextDiagnostics.missingBoth += 1;
          nextIssues.push({
            recordId: recordPid,
            title: titleVal,
            reason: 'missing_both',
            startValue: startRaw,
            endValue: endRaw,
          });
          continue;
        }
        if (!hasStart) {
          nextDiagnostics.missingStartOnly += 1;
          nextIssues.push({
            recordId: recordPid,
            title: titleVal,
            reason: 'missing_start',
            startValue: startRaw,
            endValue: endRaw,
          });
          continue;
        }
        if (!hasEnd) {
          nextDiagnostics.missingEndOnly += 1;
          nextIssues.push({
            recordId: recordPid,
            title: titleVal,
            reason: 'missing_end',
            startValue: startRaw,
            endValue: endRaw,
          });
          continue;
        }

        const startVal = new Date(String(startRaw));
        const endVal = new Date(String(endRaw));
        if (isNaN(startVal.getTime()) || isNaN(endVal.getTime())) {
          nextDiagnostics.invalidDateRecords += 1;
          nextIssues.push({
            recordId: recordPid,
            title: titleVal,
            reason: 'invalid_date',
            startValue: startRaw,
            endValue: endRaw,
          });
          continue;
        }

        const progressVal = progressField
          ? Math.max(0, Math.min(100, Number(record[progressField]) || 0))
          : 0;

        // Parse dependencies (comma-separated IDs)
        const deps: string[] = [];
        if (dependencyField && record[dependencyField]) {
          const depValue = record[dependencyField];
          if (Array.isArray(depValue)) {
            deps.push(...depValue.map(String));
          } else if (typeof depValue === 'string') {
            deps.push(
              ...depValue
                .split(',')
                .map((s) => s.trim())
                .filter(Boolean),
            );
          }
        }

        const isCritical =
          criticalPathIds.has(String(record.id)) || criticalPathIds.has(String(record.pid));
        nextDiagnostics.validRecords += 1;

        ganttTasks.push({
          id: internalRecordId,
          name: titleVal,
          type: 'task',
          start: startVal,
          end: endVal < startVal ? startVal : endVal,
          progress: progressVal,
          dependencies: deps.length > 0 ? deps : [],
          isDisabled: false,
          styles: isCritical
            ? {
                progressColor: '#DC2626',
                progressSelectedColor: '#B91C1C',
                backgroundColor: '#FCA5A5',
                backgroundSelectedColor: '#F87171',
              }
            : {
                progressColor: '#3B82F6',
                progressSelectedColor: '#2563EB',
                backgroundColor: '#93C5FD',
                backgroundSelectedColor: '#60A5FA',
              },
        });
      }

      setDiagnostics(nextDiagnostics);
      setIssueRecords(nextIssues);
      setTasks(ganttTasks);
      setTaskRecordPidMap(nextTaskRecordPidMap);
    } catch (err) {
      if (controller.signal.aborted) return;
      setError(err instanceof Error ? err.message : 'Failed to fetch gantt data');
    } finally {
      if (!controller.signal.aborted) {
        setLoading(false);
      }
    }
  }, [
    modelCode,
    startDateField,
    endDateField,
    titleField,
    progressField,
    dependencyField,
    linkageFilters,
    criticalPathIds,
  ]);

  // Fetch critical path data when dependency field is configured
  const fetchCriticalPath = useCallback(async () => {
    if (!dependencyField) return;
    try {
      const result = await fetchResult<{
        criticalPathNodeIds: string[];
        scheduleMap: Record<string, Record<string, number>>;
        totalDuration: number;
      }>('/meta/schedule/critical-path', {
        params: {
          modelCode,
          projectId: '',
          dependencyField,
        },
      });
      if (result.code === '0' && result.data?.criticalPathNodeIds) {
        setCriticalPathIds(new Set(result.data.criticalPathNodeIds));
      }
    } catch {
      // CPM is non-critical — silently degrade to no highlighting
    }
  }, [modelCode, dependencyField]);

  useEffect(() => {
    fetchTasks();
    if (dependencyField) {
      fetchCriticalPath();
    }
    return () => {
      abortRef.current?.abort();
    };
  }, [fetchTasks, dependencyField, fetchCriticalPath]);

  // Handle task click
  const handleTaskClick = useCallback(
    (task: GanttTask) => {
      const pid = taskRecordPidMap[task.id] || task.id;
      onTaskClick?.(pid);
    },
    [onTaskClick, taskRecordPidMap],
  );

  // Handle date change via drag
  const handleDateChange = useCallback(
    async (task: GanttTask) => {
      if (!startDateField || !endDateField) return;

      try {
        const startStr = task.start.toISOString().split('T')[0];
        const endStr = task.end.toISOString().split('T')[0];

        const updateData: Record<string, unknown> = {
          [startDateField]: startStr,
          [endDateField]: endStr,
        };

        await dynamicService.update(modelCode, task.id, updateData);

        // Update local state
        setTasks((prev) =>
          prev.map((t) => (t.id === task.id ? { ...t, start: task.start, end: task.end } : t)),
        );

        onTaskDateChange?.(task.id, startStr, endStr);
      } catch {
        // Revert on error by refetching
        fetchTasks();
      }
    },
    [modelCode, startDateField, endDateField, onTaskDateChange, fetchTasks],
  );

  // Handle progress change via drag
  const handleProgressChange = useCallback(
    async (task: GanttTask) => {
      if (!progressField) return;

      try {
        await dynamicService.update(modelCode, task.id, {
          [progressField]: Math.round(task.progress),
        });

        setTasks((prev) =>
          prev.map((t) => (t.id === task.id ? { ...t, progress: task.progress } : t)),
        );

        onTaskProgressChange?.(task.id, Math.round(task.progress));
      } catch {
        fetchTasks();
      }
    },
    [modelCode, progressField, onTaskProgressChange, fetchTasks],
  );

  // Column width based on view mode
  const columnWidth = useMemo(() => {
    switch (viewMode) {
      case 'Month':
        return 300;
      case 'Week':
        return 250;
      default:
        return 65;
    }
  }, [viewMode]);

  // No required fields configured
  if (!startDateField || !endDateField) {
    return (
      <ViewEmptyState
        variant="not-configured"
        title="Gantt chart not configured"
        description="Please configure Start Date Field and End Date Field to display the Gantt view."
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
        title="Failed to load gantt data"
        error={error}
        onRetry={fetchTasks}
        className={className}
      />
    );
  }

  return (
    <div className={cn('rounded-lg border border-gray-200 bg-white', className)}>
      <div
        data-testid="gantt-view-status"
        className="mx-4 mt-3 rounded-md border border-blue-200 bg-blue-50 px-3 py-2 text-xs text-blue-800"
      >
        Switched to Gantt view. Renderable tasks: {diagnostics.validRecords}/
        {diagnostics.totalRecords}.
      </div>

      <DataLimitBanner
        fetchedCount={diagnostics.totalRecords}
        totalCount={apiTotal}
        onSwitchToTableView={onSwitchToTableView}
        className="mx-4 mt-2"
      />

      {/* Toolbar */}
      <div className="flex items-center justify-between border-b border-gray-200 px-4 py-2">
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium text-gray-700">{tasks.length} tasks</span>
          {criticalPathIds.size > 0 && (
            <span className="flex items-center gap-1 text-xs text-red-600">
              <span className="inline-block h-3 w-3 rounded bg-red-400" />
              Critical path ({criticalPathIds.size})
            </span>
          )}
          {loading && (
            <span className="h-4 w-4 animate-spin rounded-full border-2 border-blue-200 border-t-blue-600" />
          )}
        </div>
        <div className="flex items-center gap-1">
          {(['Day', 'Week', 'Month'] as GanttViewMode[]).map((mode) => (
            <button
              key={mode}
              type="button"
              onClick={() => setViewMode(mode)}
              className={cn(
                'rounded-md px-3 py-1 text-xs transition-colors',
                viewMode === mode
                  ? 'bg-blue-100 font-medium text-blue-700'
                  : 'text-gray-500 hover:bg-gray-100',
              )}
            >
              {mode}
            </button>
          ))}
        </div>
      </div>

      {startDateField === endDateField && (
        <div
          data-testid="gantt-config-warning"
          className="mx-4 mt-3 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800"
        >
          Gantt configuration warning: Start Date Field and End Date Field are both
          <span className="font-semibold"> {startDateField}</span>. You usually need two different
          fields.
        </div>
      )}

      {/* Gantt Chart */}
      <div className="overflow-x-auto">
        {tasks.length > 0 ? (
          <Gantt
            tasks={tasks}
            viewMode={VIEW_MODE_MAP[viewMode]}
            onDateChange={handleDateChange}
            onProgressChange={progressField ? handleProgressChange : undefined}
            onClick={handleTaskClick}
            onDoubleClick={handleTaskClick}
            columnWidth={columnWidth}
            listCellWidth="155px"
            rowHeight={42}
            barCornerRadius={4}
            barFill={65}
            fontSize="12px"
            todayColor="rgba(59, 130, 246, 0.06)"
            locale="en"
          />
        ) : !loading ? (
          <ViewDiagnostics
            totalRecords={diagnostics.totalRecords}
            validRecords={diagnostics.validRecords}
            categories={[
              { key: 'missing_both', label: 'Missing both dates', count: diagnostics.missingBoth },
              {
                key: 'missing_start',
                label: 'Missing start date',
                count: diagnostics.missingStartOnly,
              },
              { key: 'missing_end', label: 'Missing end date', count: diagnostics.missingEndOnly },
              {
                key: 'invalid_date',
                label: 'Invalid date value',
                count: diagnostics.invalidDateRecords,
              },
            ]}
            issues={issueRecords.map((ir) => ({
              recordId: ir.recordId,
              title: ir.title,
              reason: ir.reason,
              details: { startValue: ir.startValue, endValue: ir.endValue },
            }))}
            fieldMapping={{ 'Start Date': startDateField!, 'End Date': endDateField! }}
            onRecordClick={onTaskClick}
            onOpenViewConfig={onOpenViewConfig}
            onSwitchToTableView={onSwitchToTableView}
            onRefresh={fetchTasks}
          />
        ) : null}
      </div>
    </div>
  );
};

export default GanttView;
