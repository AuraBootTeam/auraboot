/**
 * Export panel for NamedQuery test results.
 * Supports Excel/CSV/JSON sync export and async export for large datasets.
 */

import React, { useState, useCallback, useRef, useEffect } from 'react';
import { namedQueryService } from '~/services/namedQueryService';
import type { NamedQueryTestResult, ExportTaskDTO } from '~/services/namedQueryService';

type ExportFormat = 'excel' | 'csv' | 'json';

interface ExportPanelProps {
  queryCode: string;
  testResult: NamedQueryTestResult;
  whereJson: string;
  orderJson: string;
}

const ASYNC_THRESHOLD = 5000;

export default function ExportPanel({
  queryCode,
  testResult,
  whereJson,
  orderJson,
}: ExportPanelProps) {
  const [format, setFormat] = useState<ExportFormat>('excel');
  const [exporting, setExporting] = useState(false);
  const [asyncTask, setAsyncTask] = useState<ExportTaskDTO | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  // Clean up polling on unmount
  useEffect(() => {
    return () => {
      if (pollRef.current) clearInterval(pollRef.current);
    };
  }, []);

  const parseJsonSafe = (json: string): any => {
    if (!json.trim()) return undefined;
    try {
      return JSON.parse(json);
    } catch {
      return undefined;
    }
  };

  const totalRows = testResult.resultCount ?? 0;
  const isLargeDataset = totalRows > ASYNC_THRESHOLD;

  const handleExport = useCallback(async () => {
    setExporting(true);
    try {
      const request = {
        format,
        whereConditions: parseJsonSafe(whereJson),
        orderConditions: parseJsonSafe(orderJson),
        includeHeader: true,
      };

      if (isLargeDataset) {
        // Async export
        const task = await namedQueryService.submitAsyncExport(queryCode, request);
        setAsyncTask(task);
        // Start polling
        pollRef.current = setInterval(async () => {
          try {
            const status = await namedQueryService.getExportTaskStatus(task.pid);
            setAsyncTask(status);
            if (
              status.status === 'completed' ||
              status.status === 'failed' ||
              status.status === 'expired'
            ) {
              if (pollRef.current) clearInterval(pollRef.current);
              pollRef.current = null;
            }
          } catch {
            if (pollRef.current) clearInterval(pollRef.current);
            pollRef.current = null;
          }
        }, 2000);
      } else {
        // Sync export
        const result = await namedQueryService.exportData(queryCode, request);
        if (result.downloadUrl) {
          namedQueryService.downloadExport(result.downloadUrl);
        }
      }
    } catch (error: any) {
      console.error('Export failed:', error);
    } finally {
      setExporting(false);
    }
  }, [queryCode, format, whereJson, orderJson, isLargeDataset]);

  const handleDownload = useCallback(() => {
    if (asyncTask?.downloadUrl) {
      namedQueryService.downloadExport(asyncTask.downloadUrl);
    }
  }, [asyncTask]);

  return (
    <div className="mt-4 rounded-md border border-gray-200 bg-gray-50 p-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <span className="text-sm font-medium text-gray-700">导出数据</span>
          <div className="flex overflow-hidden rounded-md border border-gray-300 bg-white">
            {(['excel', 'csv', 'json'] as ExportFormat[]).map((f) => (
              <button
                key={f}
                type="button"
                onClick={() => setFormat(f)}
                className={`px-3 py-1.5 text-xs font-medium ${
                  format === f
                    ? 'border-blue-200 bg-blue-50 text-blue-700'
                    : 'text-gray-600 hover:bg-gray-50'
                } ${f !== 'excel' ? 'border-l border-gray-300' : ''}`}
              >
                {f}
              </button>
            ))}
          </div>
          {isLargeDataset && (
            <span className="rounded bg-orange-50 px-2 py-1 text-xs text-orange-600">
              大数据量 ({totalRows.toLocaleString()} 行)，将使用异步导出
            </span>
          )}
        </div>

        <button
          type="button"
          onClick={handleExport}
          disabled={exporting}
          className="rounded-md bg-green-600 px-4 py-2 text-sm text-white hover:bg-green-700 disabled:opacity-50"
        >
          {exporting ? '导出中...' : '导出'}
        </button>
      </div>

      {/* Async task progress */}
      {asyncTask && (
        <div className="mt-3">
          <div className="flex items-center gap-3">
            <div className="flex-1">
              <div className="mb-1 flex justify-between text-xs text-gray-600">
                <span>
                  {asyncTask.status === 'pending' && '等待中...'}
                  {asyncTask.status === 'running' &&
                    `导出中 ${asyncTask.processedRows ?? 0}/${asyncTask.totalRows ?? '?'}`}
                  {asyncTask.status === 'completed' && '导出完成'}
                  {asyncTask.status === 'failed' &&
                    `导出失败: ${asyncTask.errorMessage || '未知错误'}`}
                  {asyncTask.status === 'expired' && '任务已过期'}
                </span>
                <span>{asyncTask.progress ?? 0}%</span>
              </div>
              <div className="h-2 w-full rounded-full bg-gray-200">
                <div
                  className={`h-2 rounded-full transition-all ${
                    asyncTask.status === 'failed'
                      ? 'bg-red-500'
                      : asyncTask.status === 'completed'
                        ? 'bg-green-500'
                        : 'bg-blue-500'
                  }`}
                  style={{ width: `${asyncTask.progress ?? 0}%` }}
                />
              </div>
            </div>
            {asyncTask.status === 'completed' && asyncTask.downloadUrl && (
              <button
                type="button"
                onClick={handleDownload}
                className="rounded-md bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700"
              >
                下载文件
                {asyncTask.fileSize ? ` (${(asyncTask.fileSize / 1024 / 1024).toFixed(1)}MB)` : ''}
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
