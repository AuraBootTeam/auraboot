/**
 * PreviewModal Component
 *
 * Device-responsive preview for PageSchema V2 pages.
 * Renders schema.blocks uniformly via a LayoutContainer dispatching on layout.type.
 *
 * @since 5.0.0
 */

import React, { useCallback, useMemo } from 'react';
import type { PageSchema, DslBlock, PageLayout } from '~/plugins/core-designer/components/studio/domain/dsl/types';

/**
 * Device preset configurations
 */
const DEVICE_PRESETS = {
  desktop: { width: 1440, height: 900, label: 'Desktop' },
  tablet: { width: 768, height: 1024, label: 'Tablet' },
  mobile: { width: 375, height: 812, label: 'Mobile' },
} as const;

type DeviceType = keyof typeof DEVICE_PRESETS;

/**
 * PreviewModal props
 */
export interface PreviewModalProps {
  isOpen: boolean;
  onClose: () => void;
  schema?: PageSchema;
  pageTitle?: string;
}

/**
 * PreviewModal component
 */
export const PreviewModal: React.FC<PreviewModalProps> = ({
  isOpen,
  onClose,
  schema,
  pageTitle,
}) => {
  const [device, setDevice] = React.useState<DeviceType>('desktop');
  const [showGrid, setShowGrid] = React.useState(false);

  const deviceDimensions = useMemo(() => DEVICE_PRESETS[device], [device]);

  // Count total blocks for footer
  const elementCount = useMemo(() => schema?.blocks?.length ?? 0, [schema]);

  // Render page content using V2 flat blocks + layout
  const renderPageContent = useCallback(() => {
    if (!schema || schema.blocks.length === 0) return <EmptyPagePlaceholder />;
    return (
      <LayoutContainer layout={schema.layout} showGrid={showGrid}>
        {schema.blocks.map((block) => (
          <BlockPreview key={block.id} block={block} showGrid={showGrid} />
        ))}
      </LayoutContainer>
    );
  }, [schema, showGrid]);

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex flex-col">
      {/* Backdrop */}
      <div className="absolute inset-0 bg-black/60" onClick={onClose} />

      {/* Modal Container */}
      <div className="relative m-4 flex flex-1 flex-col overflow-hidden rounded-xl bg-white shadow-2xl">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-200 bg-gray-50 px-6 py-4">
          <div className="flex items-center gap-4">
            <div className="flex items-center gap-2">
              <svg
                className="h-5 w-5 text-blue-600"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                />
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"
                />
              </svg>
              <h2 className="text-lg font-semibold text-gray-900">Preview</h2>
            </div>
            {pageTitle && <span className="text-sm text-gray-500">· {pageTitle}</span>}
            {schema && (
              <span className="rounded bg-blue-100 px-2 py-0.5 text-xs text-blue-700">
                {schema.kind}
              </span>
            )}
          </div>

          {/* Device Selector */}
          <div className="flex items-center gap-3">
            <div className="flex items-center rounded-lg bg-gray-100 p-1">
              {(Object.keys(DEVICE_PRESETS) as DeviceType[]).map((d) => (
                <button
                  key={d}
                  onClick={() => setDevice(d)}
                  className={`rounded-md px-3 py-1.5 text-sm transition-colors ${
                    device === d
                      ? 'bg-white text-gray-900 shadow-sm'
                      : 'text-gray-600 hover:text-gray-900'
                  }`}
                >
                  {DEVICE_PRESETS[d].label}
                </button>
              ))}
            </div>

            <label className="flex cursor-pointer items-center gap-2 text-sm text-gray-600">
              <input
                type="checkbox"
                checked={showGrid}
                onChange={(e) => setShowGrid(e.target.checked)}
                className="h-4 w-4 rounded border-gray-300 text-blue-600"
              />
              Grid
            </label>

            <button
              onClick={onClose}
              className="rounded-lg p-2 text-gray-500 transition-colors hover:bg-gray-200"
            >
              <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M6 18L18 6M6 6l12 12"
                />
              </svg>
            </button>
          </div>
        </div>

        {/* Preview Content */}
        <div className="flex flex-1 items-center justify-center overflow-auto bg-gray-100 p-8">
          <div
            className="overflow-hidden rounded-lg bg-white shadow-lg"
            style={{
              width: deviceDimensions.width,
              maxWidth: '100%',
              minHeight: deviceDimensions.height,
              maxHeight: 'calc(100vh - 200px)',
              transition: 'width 300ms ease, min-height 300ms ease',
            }}
          >
            {/* Device Frame */}
            <div className="flex items-center gap-2 border-b border-gray-200 bg-gray-50 px-4 py-2">
              <div className="flex gap-1.5">
                <div className="h-3 w-3 rounded-full bg-red-400" />
                <div className="h-3 w-3 rounded-full bg-yellow-400" />
                <div className="h-3 w-3 rounded-full bg-green-400" />
              </div>
              <div className="flex flex-1 justify-center">
                <div className="rounded bg-gray-200 px-4 py-1 text-xs text-gray-600">
                  {deviceDimensions.width} x {deviceDimensions.height}
                </div>
              </div>
            </div>

            {/* Page Content */}
            <div className="overflow-auto" style={{ height: deviceDimensions.height - 40 }}>
              {renderPageContent()}
            </div>
          </div>
        </div>

        {/* Footer */}
        <div className="flex items-center justify-between border-t border-gray-200 bg-gray-50 px-6 py-3">
          <div className="text-sm text-gray-500">{elementCount} blocks</div>
          <div className="flex items-center gap-3">
            <button
              onClick={onClose}
              className="rounded-lg px-4 py-2 text-sm text-gray-700 transition-colors hover:bg-gray-200"
            >
              Close
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

// =============================================================================
// Layout Container — dispatches on schema.layout.type
// =============================================================================

const LayoutContainer: React.FC<{
  layout: PageLayout;
  showGrid: boolean;
  children: React.ReactNode;
}> = ({ layout, showGrid, children }) => {
  const base = `h-full w-full p-4 ${showGrid ? 'bg-grid-pattern' : 'bg-white'}`;
  if (layout.type === 'grid') {
    return (
      <div className={`${base} grid gap-4`} style={{ gridTemplateColumns: `repeat(${layout.cols}, minmax(0, 1fr))` }}>
        {children}
      </div>
    );
  }
  // default: stack
  return <div className={`${base} flex flex-col gap-4`}>{children}</div>;
};

// =============================================================================
// Block Preview
// =============================================================================

const BlockPreview: React.FC<{ block: DslBlock; showGrid: boolean }> = ({ block, showGrid }) => {
  const borderClass = showGrid
    ? 'border border-dashed border-blue-300 bg-blue-50/30'
    : 'border border-gray-200 bg-gray-50';

  switch (block.blockType) {
    case 'filters':
      return (
        <div className={`${borderClass} rounded-lg p-3`}>
          <div className="mb-2 text-[10px] text-gray-400 uppercase">Filters</div>
          <div className="grid grid-cols-3 gap-2">
            {(block.fields || []).slice(0, 6).map((field, i) => {
              const fieldName = typeof field === 'string' ? field : field.field;
              return (
                <div key={i} className="space-y-1">
                  <label className="text-xs text-gray-500">{fieldName}</label>
                  <div className="flex h-8 items-center rounded border border-gray-300 bg-white px-2">
                    <span className="text-xs text-gray-400">...</span>
                  </div>
                </div>
              );
            })}
          </div>
          <div className="mt-3 flex justify-end gap-2">
            <div className="rounded bg-gray-100 px-3 py-1.5 text-xs text-gray-600">Reset</div>
            <div className="rounded bg-blue-600 px-3 py-1.5 text-xs text-white">Search</div>
          </div>
        </div>
      );

    case 'table':
      return (
        <div className={`${borderClass} overflow-hidden rounded-lg`}>
          <div className="px-3 pt-2 text-[10px] text-gray-400 uppercase">Data Table</div>
          <table className="mt-1 w-full text-sm">
            <thead>
              <tr className="bg-gray-100 text-left">
                {(block.columns || []).slice(0, 5).map((col, i) => {
                  const colName = typeof col === 'string' ? col : col.field;
                  return (
                    <th key={i} className="px-3 py-2 text-xs font-medium text-gray-600">
                      {colName}
                    </th>
                  );
                })}
              </tr>
            </thead>
            <tbody>
              {[1, 2, 3].map((row) => (
                <tr key={row} className="border-t border-gray-100">
                  {(block.columns || []).slice(0, 5).map((_, i) => (
                    <td key={i} className="px-3 py-2">
                      <div className="h-4 w-3/4 rounded bg-gray-100" />
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      );

    case 'form-section':
    case 'detail-section':
      return (
        <div className={`${borderClass} rounded-lg p-3`}>
          {block.title && (
            <div className="mb-2 text-sm font-medium text-gray-700">{block.title}</div>
          )}
          <div className="grid grid-cols-2 gap-3">
            {(block.fields || []).slice(0, 8).map((field, i) => {
              const fieldName = typeof field === 'string' ? field : field.field;
              return (
                <div key={i} className="space-y-1">
                  <label className="text-xs text-gray-500">{fieldName}</label>
                  <div className="flex h-8 items-center rounded border border-gray-300 bg-white px-2">
                    <span className="text-xs text-gray-400">...</span>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      );

    case 'toolbar':
    case 'form-buttons':
      return (
        <div className={`${borderClass} rounded-lg p-3`}>
          <div className="flex gap-2">
            {(block.buttons || block.actions || []).slice(0, 4).map((btn, i) => {
              let label: string;
              let isPrimary: boolean;
              if (typeof btn === 'string') {
                label = btn;
                isPrimary = i === 0;
              } else {
                const labelFromObject =
                  typeof btn.label === 'object' && btn.label !== null
                    ? btn.label['zh-CN'] ?? btn.label['en'] ?? btn.label['en-US']
                    : typeof btn.label === 'string'
                      ? btn.label
                      : undefined;
                const codeStr =
                  btn.code ?? (typeof btn.action === 'string' ? btn.action : '') ?? '';
                label = labelFromObject ?? codeStr;
                isPrimary = i === 0 || btn.primary === true || btn.type === 'primary';
              }
              return (
                <div
                  key={i}
                  className={`rounded px-3 py-1.5 text-xs ${
                    isPrimary ? 'bg-blue-600 text-white' : 'bg-gray-100 text-gray-600'
                  }`}
                >
                  {label}
                </div>
              );
            })}
          </div>
        </div>
      );

    default:
      return (
        <div className={`${borderClass} rounded-lg p-3`}>
          <div className="flex items-center gap-2">
            <div className="flex h-8 w-8 items-center justify-center rounded bg-gray-200">
              <svg
                className="h-4 w-4 text-gray-400"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M4 6h16M4 12h16M4 18h16"
                />
              </svg>
            </div>
            <div>
              <div className="text-sm text-gray-700">{block.title || block.blockType}</div>
              <div className="text-xs text-gray-400">{block.blockType}</div>
            </div>
          </div>
        </div>
      );
  }
};

// =============================================================================
// Empty placeholder
// =============================================================================

const EmptyPagePlaceholder: React.FC = () => (
  <div className="flex h-full w-full items-center justify-center text-gray-400">
    <div className="text-center">
      <svg
        className="mx-auto mb-3 h-16 w-16 text-gray-300"
        fill="none"
        stroke="currentColor"
        viewBox="0 0 24 24"
      >
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth={1.5}
          d="M9 17V7m0 10a2 2 0 01-2 2H5a2 2 0 01-2-2V7a2 2 0 012-2h2a2 2 0 012 2m0 10a2 2 0 002 2h2a2 2 0 002-2M9 7a2 2 0 012-2h2a2 2 0 012 2m0 10V7m0 10a2 2 0 002 2h2a2 2 0 002-2V7a2 2 0 00-2-2h-2a2 2 0 00-2 2"
        />
      </svg>
      <p className="text-sm font-medium">Empty page</p>
      <p className="mt-1 text-xs">Add blocks or components to start</p>
    </div>
  </div>
);

export default PreviewModal;
