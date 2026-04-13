/**
 * Linkage Configuration Component
 * Configure chart interaction and filter propagation
 */

import React from 'react';
import type { LinkageConfig as LinkageConfigType } from '../types';

interface LinkageConfigProps {
  value: LinkageConfigType;
  onChange: (config: LinkageConfigType) => void;
  availableGroups?: string[];
}

/** Default linkage groups */
const DEFAULT_GROUPS = [
  { value: 'default', label: '默认分组' },
  { value: 'group-a', label: '分组 A' },
  { value: 'group-b', label: '分组 B' },
  { value: 'group-c', label: '分组 C' },
];

export const LinkageConfig: React.FC<LinkageConfigProps> = ({
  value,
  onChange,
  availableGroups,
}) => {
  const groups = availableGroups
    ? availableGroups.map((g) => ({ value: g, label: g }))
    : DEFAULT_GROUPS;

  const handleChange = (field: keyof LinkageConfigType, newValue: unknown) => {
    onChange({
      ...value,
      [field]: newValue,
    });
  };

  return (
    <div className="space-y-3">
      {/* Enable linkage */}
      <label className="flex cursor-pointer items-center gap-2">
        <input
          type="checkbox"
          checked={value.enabled ?? false}
          onChange={(e) => handleChange('enabled', e.target.checked)}
          className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
        />
        <span className="text-sm text-gray-700">启用图表联动</span>
      </label>

      {value.enabled && (
        <>
          {/* Linkage group */}
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">联动分组</label>
            <select
              value={value.groupId || 'default'}
              onChange={(e) => handleChange('groupId', e.target.value)}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
            >
              {groups.map((group) => (
                <option key={group.value} value={group.value}>
                  {group.label}
                </option>
              ))}
            </select>
            <p className="mt-1 text-xs text-gray-500">同一分组内的图表可以互相联动</p>
          </div>

          {/* Emit filter */}
          <label className="flex cursor-pointer items-center gap-2">
            <input
              type="checkbox"
              checked={value.emitFilter ?? false}
              onChange={(e) => handleChange('emitFilter', e.target.checked)}
              className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
            />
            <div>
              <span className="text-sm text-gray-700">发送过滤器</span>
              <p className="text-xs text-gray-500">点击图表时向其他图表发送过滤条件</p>
            </div>
          </label>

          {/* Receive filter */}
          <label className="flex cursor-pointer items-center gap-2">
            <input
              type="checkbox"
              checked={value.receiveFilter ?? false}
              onChange={(e) => handleChange('receiveFilter', e.target.checked)}
              className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
            />
            <div>
              <span className="text-sm text-gray-700">接收过滤器</span>
              <p className="text-xs text-gray-500">响应其他图表发送的过滤条件</p>
            </div>
          </label>
        </>
      )}
    </div>
  );
};

export default LinkageConfig;
