/**
 * Page Mode Selector Component
 *
 * UI for selecting between floor, form, and grid modes.
 *
 * @since 3.2.0
 */

import React, { useState, useCallback } from 'react';
import type { PageMode, PageModeConfig, FormLayoutConfig } from './types';
import { PAGE_MODES, FORM_COLUMN_PRESETS, LABEL_POSITIONS } from './modes';

interface PageModeSelectorProps {
  /** Current mode */
  currentMode: PageMode;
  /** Form layout config (for form mode) */
  formLayout?: FormLayoutConfig;
  /** On mode change */
  onModeChange?: (mode: PageMode) => void;
  /** On form layout change */
  onFormLayoutChange?: (layout: FormLayoutConfig) => void;
  /** Display as compact selector */
  compact?: boolean;
  /** Disable mode switching */
  disabled?: boolean;
}

/**
 * Page Mode Selector Component
 */
export const PageModeSelector: React.FC<PageModeSelectorProps> = ({
  currentMode,
  formLayout,
  onModeChange,
  onFormLayoutChange,
  compact = false,
  disabled = false,
}) => {
  const [showConfirm, setShowConfirm] = useState(false);
  const [pendingMode, setPendingMode] = useState<PageMode | null>(null);

  const handleModeClick = useCallback(
    (mode: PageMode) => {
      if (disabled || mode === currentMode) return;

      // Show confirmation if switching modes
      setPendingMode(mode);
      setShowConfirm(true);
    },
    [currentMode, disabled],
  );

  const confirmModeChange = useCallback(() => {
    if (pendingMode) {
      onModeChange?.(pendingMode);
    }
    setShowConfirm(false);
    setPendingMode(null);
  }, [pendingMode, onModeChange]);

  const cancelModeChange = useCallback(() => {
    setShowConfirm(false);
    setPendingMode(null);
  }, []);

  if (compact) {
    return (
      <CompactSelector
        currentMode={currentMode}
        onModeChange={handleModeClick}
        disabled={disabled}
      />
    );
  }

  return (
    <div className="p-4">
      <h3 className="mb-4 text-sm font-semibold text-gray-900">页面模式</h3>

      {/* Mode cards */}
      <div className="mb-4 grid grid-cols-3 gap-3">
        {Object.values(PAGE_MODES).map((config) => (
          <ModeCard
            key={config.mode}
            config={config}
            isSelected={currentMode === config.mode}
            onClick={() => handleModeClick(config.mode)}
            disabled={disabled}
          />
        ))}
      </div>

      {/* Description */}
      <div className="mb-4 text-xs text-gray-500">{PAGE_MODES[currentMode].description}</div>

      {/* Form layout options (only for form mode) */}
      {currentMode === 'form' && formLayout && onFormLayoutChange && (
        <FormLayoutOptions layout={formLayout} onChange={onFormLayoutChange} />
      )}

      {/* Mode switch confirmation dialog */}
      {showConfirm && pendingMode && (
        <ConfirmDialog
          fromMode={currentMode}
          toMode={pendingMode}
          onConfirm={confirmModeChange}
          onCancel={cancelModeChange}
        />
      )}
    </div>
  );
};

/**
 * Mode card component
 */
interface ModeCardProps {
  config: PageModeConfig;
  isSelected: boolean;
  onClick: () => void;
  disabled?: boolean;
}

const ModeCard: React.FC<ModeCardProps> = ({ config, isSelected, onClick, disabled }) => (
  <button
    type="button"
    onClick={onClick}
    disabled={disabled}
    className={`relative rounded-lg border-2 p-3 text-center transition-all ${disabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer'} ${
      isSelected
        ? 'border-blue-500 bg-blue-50'
        : 'border-gray-200 hover:border-gray-300 hover:bg-gray-50'
    } `}
  >
    {/* Selected indicator */}
    {isSelected && (
      <span className="absolute top-1 right-1 flex h-4 w-4 items-center justify-center rounded-full bg-blue-500 text-[10px] text-white">
        ✓
      </span>
    )}

    {/* Icon */}
    <div className="mb-1 text-2xl">{config.icon}</div>

    {/* Name */}
    <div className="text-xs font-medium text-gray-700">{config.name}</div>

    {/* Structure hint */}
    <div className="mt-0.5 text-[10px] text-gray-400">{config.structure.levels.join(' → ')}</div>
  </button>
);

/**
 * Compact mode selector
 */
interface CompactSelectorProps {
  currentMode: PageMode;
  onModeChange: (mode: PageMode) => void;
  disabled?: boolean;
}

const CompactSelector: React.FC<CompactSelectorProps> = ({
  currentMode,
  onModeChange,
  disabled,
}) => (
  <div className="inline-flex items-center rounded-md bg-gray-100 p-0.5">
    {Object.values(PAGE_MODES).map((config) => (
      <button
        key={config.mode}
        type="button"
        onClick={() => onModeChange(config.mode)}
        disabled={disabled}
        className={`rounded px-2 py-1 text-xs transition-colors ${disabled ? 'cursor-not-allowed' : ''} ${
          currentMode === config.mode
            ? 'bg-white text-gray-700 shadow-sm'
            : 'text-gray-500 hover:text-gray-700'
        } `}
        title={config.description}
      >
        {config.icon} {config.name}
      </button>
    ))}
  </div>
);

/**
 * Form layout options
 */
interface FormLayoutOptionsProps {
  layout: FormLayoutConfig;
  onChange: (layout: FormLayoutConfig) => void;
}

const FormLayoutOptions: React.FC<FormLayoutOptionsProps> = ({ layout, onChange }) => (
  <div className="space-y-3 border-t border-gray-100 pt-3">
    <h4 className="text-xs font-medium text-gray-700">表单布局</h4>

    {/* Column selector */}
    <div>
      <label className="mb-1 block text-xs text-gray-500">列数</label>
      <div className="flex gap-1">
        {FORM_COLUMN_PRESETS.map((preset) => (
          <button
            key={preset.columns}
            type="button"
            onClick={() => onChange({ ...layout, columns: preset.columns })}
            className={`flex-1 rounded border px-2 py-1.5 text-xs transition-colors ${
              layout.columns === preset.columns
                ? 'border-blue-500 bg-blue-50 text-blue-700'
                : 'border-gray-200 hover:border-gray-300'
            } `}
            title={preset.description}
          >
            {preset.label}
          </button>
        ))}
      </div>
    </div>

    {/* Label position */}
    <div>
      <label className="mb-1 block text-xs text-gray-500">标签位置</label>
      <div className="flex gap-1">
        {LABEL_POSITIONS.map((pos) => (
          <button
            key={pos.value}
            type="button"
            onClick={() => onChange({ ...layout, labelPosition: pos.value })}
            className={`flex-1 rounded border px-2 py-1.5 text-xs transition-colors ${
              layout.labelPosition === pos.value
                ? 'border-blue-500 bg-blue-50 text-blue-700'
                : 'border-gray-200 hover:border-gray-300'
            } `}
            title={pos.description}
          >
            {pos.label}
          </button>
        ))}
      </div>
    </div>

    {/* Label width (only for left position) */}
    {layout.labelPosition === 'left' && (
      <div>
        <label className="mb-1 block text-xs text-gray-500">标签宽度</label>
        <input
          type="number"
          value={layout.labelWidth || 100}
          onChange={(e) => onChange({ ...layout, labelWidth: Number(e.target.value) })}
          className="w-full rounded border border-gray-200 px-2 py-1 text-xs"
          min={60}
          max={200}
          step={10}
        />
      </div>
    )}

    {/* Gutter */}
    <div>
      <label className="mb-1 block text-xs text-gray-500">间距 ({layout.gutter}px)</label>
      <input
        type="range"
        value={layout.gutter}
        onChange={(e) => onChange({ ...layout, gutter: Number(e.target.value) })}
        className="w-full"
        min={8}
        max={32}
        step={4}
      />
    </div>
  </div>
);

/**
 * Mode switch confirmation dialog
 */
interface ConfirmDialogProps {
  fromMode: PageMode;
  toMode: PageMode;
  onConfirm: () => void;
  onCancel: () => void;
}

const ConfirmDialog: React.FC<ConfirmDialogProps> = ({ fromMode, toMode, onConfirm, onCancel }) => (
  <>
    {/* Backdrop */}
    <div className="fixed inset-0 z-40 bg-black/20" onClick={onCancel} />

    {/* Dialog */}
    <div className="fixed top-1/2 left-1/2 z-50 w-80 -translate-x-1/2 -translate-y-1/2 rounded-lg bg-white p-4 shadow-xl">
      <h3 className="mb-2 text-sm font-semibold text-gray-900">切换页面模式</h3>
      <p className="mb-4 text-xs text-gray-600">
        从 <strong>{PAGE_MODES[fromMode].name}</strong> 切换到{' '}
        <strong>{PAGE_MODES[toMode].name}</strong>？
        <br />
        <br />
        部分组件可能需要调整以适应新布局。
      </p>
      <div className="flex justify-end gap-2">
        <button
          type="button"
          onClick={onCancel}
          className="rounded px-3 py-1.5 text-xs text-gray-600 hover:bg-gray-100"
        >
          取消
        </button>
        <button
          type="button"
          onClick={onConfirm}
          className="rounded bg-blue-500 px-3 py-1.5 text-xs text-white hover:bg-blue-600"
        >
          确认切换
        </button>
      </div>
    </div>
  </>
);

export default PageModeSelector;
