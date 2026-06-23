/**
 * GalleryConfigPanel Component
 *
 * Configuration panel for creating/editing GALLERY view settings.
 * Allows selecting imageField, titleField, columns, aspect ratio, and display options.
 */

import React, { useCallback } from 'react';
import type { ViewConfig } from '~/framework/smart/types/savedView';
import type { FieldOption } from './KanbanConfigPanel';
import { cn } from '~/utils/cn';

/**
 * Props for GalleryConfigPanel component
 */
export interface GalleryConfigPanelProps {
  /** Current view configuration */
  viewConfig: ViewConfig;
  /** Callback when configuration changes */
  onChange: (config: ViewConfig) => void;
  /** Available model fields for selection */
  fields: FieldOption[];
  /** Custom CSS class */
  className?: string;
}

const COLUMN_OPTIONS = [
  { value: 2, label: '2' },
  { value: 3, label: '3' },
  { value: 4, label: '4' },
  { value: 6, label: '6' },
] as const;

const ASPECT_RATIO_OPTIONS = [
  { value: 'square', label: '1:1' },
  { value: '4:3', label: '4:3' },
  { value: '16:9', label: '16:9' },
  { value: 'auto', label: '自适应' },
] as const;

/**
 * GalleryConfigPanel - Configuration UI for gallery view settings
 */
export const GalleryConfigPanel: React.FC<GalleryConfigPanelProps> = ({
  viewConfig,
  onChange,
  fields,
  className,
}) => {
  const updateConfig = useCallback(
    (partial: Partial<ViewConfig>) => {
      onChange({ ...viewConfig, ...partial });
    },
    [viewConfig, onChange],
  );

  const selectClassName =
    'w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500';

  return (
    <div className={cn('space-y-5', className)}>
      {/* Image Field (required) */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          图片字段 <span className="text-red-500">*</span>
        </label>
        <select
          value={viewConfig.galleryImageField || ''}
          onChange={(e) => updateConfig({ galleryImageField: e.target.value })}
          className={selectClassName}
        >
          <option value="">选择图片字段...</option>
          {fields.map((f) => (
            <option key={f.code} value={f.code}>
              {f.name} ({f.dataType})
            </option>
          ))}
        </select>
        <p className="mt-1 text-xs text-gray-500">选择包含图片地址或附件的字段。</p>
      </div>

      {/* Title Field */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">标题字段</label>
        <select
          value={viewConfig.galleryTitleField || ''}
          onChange={(e) => updateConfig({ galleryTitleField: e.target.value || undefined })}
          className={selectClassName}
        >
          <option value="">默认名称字段</option>
          {fields.map((f) => (
            <option key={f.code} value={f.code}>
              {f.name}
            </option>
          ))}
        </select>
      </div>

      {/* Description Field (optional) */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">描述字段</label>
        <select
          value={viewConfig.galleryDescriptionField || ''}
          onChange={(e) => updateConfig({ galleryDescriptionField: e.target.value || undefined })}
          className={selectClassName}
        >
          <option value="">不显示</option>
          {fields.map((f) => (
            <option key={f.code} value={f.code}>
              {f.name}
            </option>
          ))}
        </select>
      </div>

      {/* Columns */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">列数</label>
        <div className="flex gap-2">
          {COLUMN_OPTIONS.map((opt) => (
            <button
              key={opt.value}
              type="button"
              onClick={() => updateConfig({ galleryColumns: opt.value })}
              className={cn(
                'flex-1 rounded-md border px-3 py-2 text-sm transition-colors duration-100',
                (viewConfig.galleryColumns || 4) === opt.value
                  ? 'border-blue-500 bg-blue-50 font-medium text-blue-700'
                  : 'border-gray-300 bg-white text-gray-600 hover:bg-gray-50',
              )}
            >
              {opt.label}
            </button>
          ))}
        </div>
      </div>

      {/* Aspect Ratio */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">图片比例</label>
        <div className="flex gap-2">
          {ASPECT_RATIO_OPTIONS.map((opt) => (
            <button
              key={opt.value}
              type="button"
              onClick={() =>
                updateConfig({ galleryAspectRatio: opt.value as ViewConfig['galleryAspectRatio'] })
              }
              className={cn(
                'flex-1 rounded-md border px-3 py-2 text-sm transition-colors duration-100',
                (viewConfig.galleryAspectRatio || '4:3') === opt.value
                  ? 'border-blue-500 bg-blue-50 font-medium text-blue-700'
                  : 'border-gray-300 bg-white text-gray-600 hover:bg-gray-50',
              )}
            >
              {opt.label}
            </button>
          ))}
        </div>
      </div>

      {/* Toggle Options */}
      <div className="space-y-3 border-t border-gray-200 pt-2">
        <label className="flex cursor-pointer items-center gap-3">
          <input
            type="checkbox"
            checked={viewConfig.galleryShowTitle ?? true}
            onChange={(e) => updateConfig({ galleryShowTitle: e.target.checked })}
            className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          />
          <span className="text-sm text-gray-700">在卡片上显示标题</span>
        </label>
        <label className="flex cursor-pointer items-center gap-3">
          <input
            type="checkbox"
            checked={viewConfig.galleryShowDescription ?? false}
            onChange={(e) => updateConfig({ galleryShowDescription: e.target.checked })}
            className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          />
          <span className="text-sm text-gray-700">在卡片上显示描述</span>
        </label>
      </div>
    </div>
  );
};

export default GalleryConfigPanel;
