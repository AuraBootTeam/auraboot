/**
 * Page Mode Selector Component
 *
 * UI for selecting between floor, form, and grid modes.
 *
 * @since 3.2.0
 */

import React, { useState, useCallback, useId } from 'react';
import { useSmartText } from '~/utils/i18n';
import type { PageMode, PageModeConfig, FormLayoutConfig } from './types';
import { PAGE_MODES, FORM_COLUMN_PRESETS, LABEL_POSITIONS } from './modes';

function useLocalizedModes(): Record<PageMode, PageModeConfig> {
  const st = useSmartText();
  return Object.fromEntries(Object.entries(PAGE_MODES).map(([mode, config]) => [mode, {
    ...config,
    name: st(`$i18n:designer_page_mode.modes.${mode}.name`, config.name),
    description: st(`$i18n:designer_page_mode.modes.${mode}.description`, config.description),
  }])) as Record<PageMode, PageModeConfig>;
}

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
  const st = useSmartText();
  const modes = useLocalizedModes();
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

  const confirmation = showConfirm && pendingMode ? (
    <ConfirmDialog fromMode={currentMode} toMode={pendingMode}
      onConfirm={confirmModeChange} onCancel={cancelModeChange} />
  ) : null;

  if (compact) {
    return <>
      <CompactSelector currentMode={currentMode} onModeChange={handleModeClick} disabled={disabled} />
      {confirmation}
    </>;
  }

  return (
    <div className="p-4">
      <h3 className="mb-4 text-sm font-semibold text-gray-900">{st('$i18n:designer_page_mode.title', 'Page mode')}</h3>

      {/* Mode cards */}
      <div className="mb-4 grid grid-cols-3 gap-3">
        {Object.values(modes).map((config) => (
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
      <div className="mb-4 text-xs text-gray-500">{modes[currentMode].description}</div>

      {/* Form layout options (only for form mode) */}
      {currentMode === 'form' && formLayout && onFormLayoutChange && (
        <FormLayoutOptions layout={formLayout} onChange={onFormLayoutChange} />
      )}

      {/* Mode switch confirmation dialog */}
      {confirmation}
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
}) => {
  const modes = useLocalizedModes();
  return (
  <div className="inline-flex items-center rounded-md bg-gray-100 p-0.5">
    {Object.values(modes).map((config) => (
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
};

/**
 * Form layout options
 */
interface FormLayoutOptionsProps {
  layout: FormLayoutConfig;
  onChange: (layout: FormLayoutConfig) => void;
}

const FormLayoutOptions: React.FC<FormLayoutOptionsProps> = ({ layout, onChange }) => {
  const st = useSmartText();
  const inputId = useId();
  return (
  <div className="space-y-3 border-t border-gray-100 pt-3">
    <h4 className="text-xs font-medium text-gray-700">{st('$i18n:designer_page_mode.layout', 'Form layout')}</h4>

    {/* Column selector */}
    <div>
      <label className="mb-1 block text-xs text-gray-500">{st('$i18n:designer_page_mode.columns', 'Columns')}</label>
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
            title={st(`$i18n:designer_page_mode.column_description.${preset.columns}`, preset.description)}
          >
            {st({ i18nKey: 'designer_page_mode.column_label', params: { count: preset.columns } }, '{count} columns')}
          </button>
        ))}
      </div>
    </div>

    {/* Label position */}
    <div>
      <label className="mb-1 block text-xs text-gray-500">{st('$i18n:designer_page_mode.label_position', 'Label position')}</label>
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
            title={st(`$i18n:designer_page_mode.position.${pos.value}.description`, pos.description)}
          >
             {st(`$i18n:designer_page_mode.position.${pos.value}.label`, pos.label)}
          </button>
        ))}
      </div>
    </div>

    {/* Label width (only for left position) */}
    {layout.labelPosition === 'left' && (
      <div>
        <label htmlFor={`${inputId}-width`} className="mb-1 block text-xs text-gray-500">{st('$i18n:designer_page_mode.label_width', 'Label width')}</label>
        <input
          type="number"
          id={`${inputId}-width`}
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
      <label htmlFor={`${inputId}-gutter`} className="mb-1 block text-xs text-gray-500">{st({ i18nKey: 'designer_page_mode.gutter', params: { value: layout.gutter } }, 'Gutter ({value}px)')}</label>
      <input
        type="range"
        id={`${inputId}-gutter`}
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
};

/**
 * Mode switch confirmation dialog
 */
interface ConfirmDialogProps {
  fromMode: PageMode;
  toMode: PageMode;
  onConfirm: () => void;
  onCancel: () => void;
}

const ConfirmDialog: React.FC<ConfirmDialogProps> = ({ fromMode, toMode, onConfirm, onCancel }) => {
  const st = useSmartText();
  const modes = useLocalizedModes();
  const titleId = useId();
  return (
  <>
    {/* Backdrop */}
    <div className="fixed inset-0 z-40 bg-black/20" onClick={onCancel} />

    {/* Dialog */}
    <div role="dialog" aria-modal="true" aria-labelledby={titleId} className="fixed top-1/2 left-1/2 z-50 w-80 -translate-x-1/2 -translate-y-1/2 rounded-lg bg-white p-4 shadow-xl">
      <h3 id={titleId} className="mb-2 text-sm font-semibold text-gray-900">{st('$i18n:designer_page_mode.confirm_title', 'Switch page mode')}</h3>
      <p className="mb-4 text-xs text-gray-600">
        {st('$i18n:designer_page_mode.from', 'From')} <strong>{modes[fromMode].name}</strong> {st('$i18n:designer_page_mode.to', 'to')}{' '}
        <strong>{modes[toMode].name}</strong>？
        <br />
        <br />
        {st('$i18n:designer_page_mode.warning', 'Some components may need adjustment for the new layout.')}
      </p>
      <div className="flex justify-end gap-2">
        <button
          type="button"
          onClick={onCancel}
          className="rounded px-3 py-1.5 text-xs text-gray-600 hover:bg-gray-100"
        >
          {st('$i18n:designer_page_mode.cancel', 'Cancel')}
        </button>
        <button
          type="button"
          onClick={onConfirm}
          className="rounded bg-blue-500 px-3 py-1.5 text-xs text-white hover:bg-blue-600"
        >
          {st('$i18n:designer_page_mode.confirm', 'Confirm switch')}
        </button>
      </div>
    </div>
  </>
  );
};

export default PageModeSelector;
