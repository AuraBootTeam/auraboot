import { useState } from 'react';
import { XMarkIcon, DocumentArrowDownIcon } from '@heroicons/react/24/outline';
import type { ExportConfig } from '~/routes/reports/overview/types';

interface ExportModalProps {
  isOpen: boolean;
  onClose: () => void;
  onExport: (config: ExportConfig) => Promise<any>;
  onCheckStatus: (taskId: string) => Promise<any>;
  onDownload: (taskId: string) => Promise<any>;
  loading?: boolean;
}

export function ExportModal({ isOpen, onClose, onExport, loading = false }: ExportModalProps) {
  const [config, setConfig] = useState<ExportConfig>({
    type: 'all',
    format: 'excel',
    timeRange: 'month',
    includeCharts: true,
  });

  if (!isOpen) return null;

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onExport(config);
  };

  return (
    <div className="bg-opacity-50 fixed inset-0 z-50 h-full w-full overflow-y-auto bg-gray-600">
      <div className="relative top-20 mx-auto w-96 rounded-md border bg-white p-5 shadow-lg">
        <div className="mb-4 flex items-center justify-between">
          <h3 className="text-lg font-medium text-gray-900">导出报表</h3>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600">
            <XMarkIcon className="h-6 w-6" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="mb-2 block text-sm font-medium text-gray-700">导出格式</label>
            <select
              value={config.format}
              onChange={(e) =>
                setConfig({ ...config, format: e.target.value as 'excel' | 'pdf' | 'csv' })
              }
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
            >
              <option value="excel">Excel (.xlsx)</option>
              <option value="pdf">PDF (.pdf)</option>
              <option value="csv">CSV (.csv)</option>
            </select>
          </div>

          <div>
            <label className="mb-2 block text-sm font-medium text-gray-700">数据范围</label>
            <select
              value={config.type}
              onChange={(e) =>
                setConfig({ ...config, type: e.target.value as ExportConfig['type'] })
              }
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
            >
              <option value="overview">概览数据</option>
              <option value="device_trend">设备趋势</option>
              <option value="store_distribution">门店分布</option>
              <option value="all">全部数据</option>
            </select>
          </div>

          <div className="space-y-2">
            <label className="block text-sm font-medium text-gray-700">包含内容</label>
            <div className="space-y-2">
              <label className="flex items-center">
                <input
                  type="checkbox"
                  checked={config.includeCharts}
                  onChange={(e) => setConfig({ ...config, includeCharts: e.target.checked })}
                  className="mr-2 h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                />
                <span className="text-sm text-gray-700">包含图表</span>
              </label>
              <div>
                <label className="mb-2 block text-sm font-medium text-gray-700">时间范围</label>
                <select
                  value={config.timeRange}
                  onChange={(e) =>
                    setConfig({ ...config, timeRange: e.target.value as ExportConfig['timeRange'] })
                  }
                  className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                >
                  <option value="today">今天</option>
                  <option value="week">本周</option>
                  <option value="month">本月</option>
                  <option value="quarter">本季度</option>
                  <option value="year">本年</option>
                </select>
              </div>
            </div>
          </div>

          <div className="flex justify-end space-x-3 pt-4">
            <button
              type="button"
              onClick={onClose}
              className="rounded-md border border-gray-300 bg-gray-100 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-200 focus:ring-2 focus:ring-gray-500 focus:outline-none"
            >
              取消
            </button>
            <button
              type="submit"
              disabled={loading}
              className="flex items-center rounded-md border border-transparent bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:outline-none disabled:cursor-not-allowed disabled:opacity-50"
            >
              {loading ? (
                <>
                  <div className="mr-2 h-4 w-4 animate-spin rounded-full border-b-2 border-white"></div>
                  导出中...
                </>
              ) : (
                <>
                  <DocumentArrowDownIcon className="mr-2 h-4 w-4" />
                  开始导出
                </>
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
