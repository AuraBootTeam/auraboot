/**
 * Style Configuration Component
 * Configure chart visual styles like colors, fonts, and borders
 */

import React from 'react';

/** Color theme presets */
const COLOR_THEMES = [
  {
    value: 'default',
    label: '默认',
    colors: ['#5470c6', '#91cc75', '#fac858', '#ee6666', '#73c0de'],
  },
  {
    value: 'vintage',
    label: '复古',
    colors: ['#d87c7c', '#919e8b', '#d7ab82', '#6e7074', '#61a0a8'],
  },
  { value: 'dark', label: '暗色', colors: ['#dd6b66', '#759aa0', '#e69d87', '#8dc1a9', '#ea7e53'] },
  {
    value: 'westeros',
    label: '西部',
    colors: ['#516b91', '#59c4e6', '#edafda', '#93b7e3', '#a5e7f0'],
  },
  {
    value: 'macarons',
    label: '马卡龙',
    colors: ['#2ec7c9', '#b6a2de', '#5ab1ef', '#ffb980', '#d87a80'],
  },
];

export interface StyleSettings {
  colorTheme?: string;
  showTitle?: boolean;
  showLegend?: boolean;
  legendPosition?: 'top' | 'bottom' | 'left' | 'right';
  showLabel?: boolean;
  labelPosition?: 'inside' | 'outside' | 'top';
  borderRadius?: number;
  backgroundColor?: string;
}

interface StyleConfigProps {
  value: StyleSettings;
  onChange: (settings: StyleSettings) => void;
}

export const StyleConfig: React.FC<StyleConfigProps> = ({ value, onChange }) => {
  const handleChange = (field: keyof StyleSettings, newValue: unknown) => {
    onChange({
      ...value,
      [field]: newValue,
    });
  };

  return (
    <div className="space-y-4">
      {/* Color Theme */}
      <div>
        <label className="mb-2 block text-sm font-medium text-gray-700">配色主题</label>
        <div className="grid grid-cols-1 gap-2">
          {COLOR_THEMES.map((theme) => (
            <label
              key={theme.value}
              className={`flex cursor-pointer items-center gap-2 rounded-md border p-2 transition-colors ${
                (value.colorTheme || 'default') === theme.value
                  ? 'border-blue-500 bg-blue-50'
                  : 'border-gray-200 hover:border-gray-300'
              }`}
            >
              <input
                type="radio"
                name="colorTheme"
                value={theme.value}
                checked={(value.colorTheme || 'default') === theme.value}
                onChange={(e) => handleChange('colorTheme', e.target.value)}
                className="sr-only"
              />
              <div className="flex gap-1">
                {theme.colors.slice(0, 5).map((color, i) => (
                  <div key={i} className="h-4 w-4 rounded-sm" style={{ backgroundColor: color }} />
                ))}
              </div>
              <span className="text-sm text-gray-600">{theme.label}</span>
            </label>
          ))}
        </div>
      </div>

      {/* Show Title */}
      <label className="flex cursor-pointer items-center gap-2">
        <input
          type="checkbox"
          checked={value.showTitle ?? true}
          onChange={(e) => handleChange('showTitle', e.target.checked)}
          className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
        />
        <span className="text-sm text-gray-700">显示标题</span>
      </label>

      {/* Show Legend */}
      <label className="flex cursor-pointer items-center gap-2">
        <input
          type="checkbox"
          checked={value.showLegend ?? true}
          onChange={(e) => handleChange('showLegend', e.target.checked)}
          className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
        />
        <span className="text-sm text-gray-700">显示图例</span>
      </label>

      {/* Legend Position */}
      {(value.showLegend ?? true) && (
        <div>
          <label className="mb-1 block text-sm font-medium text-gray-700">图例位置</label>
          <select
            value={value.legendPosition || 'bottom'}
            onChange={(e) => handleChange('legendPosition', e.target.value)}
            className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          >
            <option value="top">顶部</option>
            <option value="bottom">底部</option>
            <option value="left">左侧</option>
            <option value="right">右侧</option>
          </select>
        </div>
      )}

      {/* Show Label */}
      <label className="flex cursor-pointer items-center gap-2">
        <input
          type="checkbox"
          checked={value.showLabel ?? false}
          onChange={(e) => handleChange('showLabel', e.target.checked)}
          className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
        />
        <span className="text-sm text-gray-700">显示数据标签</span>
      </label>

      {/* Label Position */}
      {value.showLabel && (
        <div>
          <label className="mb-1 block text-sm font-medium text-gray-700">标签位置</label>
          <select
            value={value.labelPosition || 'top'}
            onChange={(e) => handleChange('labelPosition', e.target.value)}
            className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          >
            <option value="inside">内部</option>
            <option value="outside">外部</option>
            <option value="top">顶部</option>
          </select>
        </div>
      )}

      {/* Border Radius */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">圆角大小</label>
        <div className="flex items-center gap-2">
          <input
            type="range"
            min="0"
            max="20"
            value={value.borderRadius ?? 8}
            onChange={(e) => handleChange('borderRadius', Number(e.target.value))}
            className="flex-1"
          />
          <span className="w-8 text-sm text-gray-500">{value.borderRadius ?? 8}px</span>
        </div>
      </div>

      {/* Background Color */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">背景颜色</label>
        <div className="flex items-center gap-2">
          <input
            type="color"
            value={value.backgroundColor || '#ffffff'}
            onChange={(e) => handleChange('backgroundColor', e.target.value)}
            className="h-10 w-10 cursor-pointer rounded border border-gray-300"
          />
          <input
            type="text"
            value={value.backgroundColor || '#ffffff'}
            onChange={(e) => handleChange('backgroundColor', e.target.value)}
            className="flex-1 rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          />
        </div>
      </div>
    </div>
  );
};

export default StyleConfig;
