/**
 * Drilldown Configuration Component
 * Configure chart click behavior: filter, navigate, modal, or dashboard
 */

import React from 'react';
import type { DrillDownConfig as DrillDownConfigType } from '~/framework/smart/types/chart';
import { BaseResourceSelect } from '~/ui/base-fields';
import { fetchPageOptions, fetchDashboardOptions } from '~/shared/services/resourceSelectService';

interface DrilldownConfigProps {
  value: DrillDownConfigType;
  onChange: (config: DrillDownConfigType) => void;
}

/** Available drilldown actions */
const DRILLDOWN_ACTIONS = [
  { value: 'filter', label: '过滤数据', description: '点击后过滤当前图表数据' },
  { value: 'navigate', label: '跳转页面', description: '点击后跳转到指定页面' },
  { value: 'modal', label: '弹窗详情', description: '点击后在弹窗中显示详情' },
  { value: 'dashboard', label: '跳转仪表盘', description: '点击后跳转到指定仪表盘' },
] as const;

export const DrilldownConfig: React.FC<DrilldownConfigProps> = ({ value, onChange }) => {
  const handleChange = (field: keyof DrillDownConfigType, newValue: unknown) => {
    onChange({
      ...value,
      [field]: newValue,
    });
  };

  return (
    <div className="space-y-3">
      {/* Enable drilldown */}
      <label className="flex cursor-pointer items-center gap-2">
        <input
          type="checkbox"
          checked={value.enabled ?? false}
          onChange={(e) => handleChange('enabled', e.target.checked)}
          className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
        />
        <span className="text-sm text-gray-700">启用点击钻取</span>
      </label>

      {value.enabled && (
        <>
          {/* Drilldown action */}
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">钻取动作</label>
            <div className="space-y-2">
              {DRILLDOWN_ACTIONS.map((action) => (
                <label key={action.value} className="flex cursor-pointer items-start gap-2">
                  <input
                    type="radio"
                    name="drilldown-action"
                    value={action.value}
                    checked={value.action === action.value}
                    onChange={(e) => handleChange('action', e.target.value)}
                    className="mt-0.5 h-4 w-4 border-gray-300 text-blue-600 focus:ring-blue-500"
                  />
                  <div>
                    <span className="text-sm text-gray-700">{action.label}</span>
                    <p className="text-xs text-gray-500">{action.description}</p>
                  </div>
                </label>
              ))}
            </div>
          </div>

          {/* Navigate action settings */}
          {value.action === 'navigate' && (
            <div className="space-y-3 pl-6">
              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700">目标页面</label>
                <BaseResourceSelect
                  value={value.targetPage || ''}
                  onChange={(val) => handleChange('targetPage', val)}
                  fetchOptions={fetchPageOptions}
                  placeholder="选择页面..."
                />
                <p className="mt-1 text-xs text-gray-500">选择要跳转的目标页面</p>
              </div>

              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700">参数映射</label>
                <input
                  type="text"
                  value={value.paramMapping ? JSON.stringify(value.paramMapping) : ''}
                  onChange={(e) => {
                    try {
                      handleChange('paramMapping', JSON.parse(e.target.value));
                    } catch {
                      // Keep empty on invalid JSON
                    }
                  }}
                  placeholder='{"id": "pid"}'
                  className="w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
                />
                <p className="mt-1 text-xs text-gray-500">点击数据字段到 URL 参数的映射关系</p>
              </div>
            </div>
          )}

          {/* Dashboard action settings */}
          {value.action === 'dashboard' && (
            <div className="space-y-3 pl-6">
              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700">目标仪表盘</label>
                <BaseResourceSelect
                  value={value.targetDashboard || ''}
                  onChange={(val) => handleChange('targetDashboard', val)}
                  fetchOptions={fetchDashboardOptions}
                  placeholder="选择仪表盘..."
                />
                <p className="mt-1 text-xs text-gray-500">选择要跳转的目标仪表盘</p>
              </div>

              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700">参数映射</label>
                <input
                  type="text"
                  value={value.paramMapping ? JSON.stringify(value.paramMapping) : ''}
                  onChange={(e) => {
                    try {
                      handleChange('paramMapping', JSON.parse(e.target.value));
                    } catch {
                      // Keep empty on invalid JSON
                    }
                  }}
                  placeholder='{"id": "pid"}'
                  className="w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
                />
                <p className="mt-1 text-xs text-gray-500">点击数据字段到 URL 参数的映射关系</p>
              </div>
            </div>
          )}

          {/* Modal action settings */}
          {value.action === 'modal' && (
            <div className="pl-6">
              <p className="text-xs text-gray-500">点击图表元素后将在弹窗中显示详细数据</p>
            </div>
          )}

          {/* Filter action settings */}
          {value.action === 'filter' && (
            <div className="space-y-3 pl-6">
              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700">钻取路径</label>
                <input
                  type="text"
                  value={value.path ? value.path.map((p) => p.dimension).join(' → ') : ''}
                  readOnly
                  placeholder="自动根据维度字段生成"
                  className="w-full rounded-md border border-gray-200 bg-gray-50 px-3 py-2 text-sm"
                />
                <p className="mt-1 text-xs text-gray-500">点击后根据维度层级逐层钻取数据</p>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
};

export default DrilldownConfig;
