/**
 * SettingsPanel Component
 *
 * Settings panel for configuring page, editor, and appearance.
 *
 * @since 3.2.0
 */

import React, { useState, useCallback, useEffect } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import {
  DEFAULT_SETTINGS,
  SETTINGS_CATEGORY_INFO,
  type SettingsCategory,
  type AllSettings,
  type PageSettings,
  type EditorSettings,
  type AppearanceSettings,
  type ExportSettings,
} from './types';

function parseInteger(value: string, fallback: number): number {
  const parsed = Number.parseInt(value, 10);
  return Number.isNaN(parsed) ? fallback : parsed;
}

/**
 * SettingsPanel props
 */
export interface SettingsPanelProps {
  /** Whether the panel is open */
  isOpen: boolean;
  /** Close callback */
  onClose: () => void;
  /** Initial settings (deep partial — merged with defaults) */
  initialSettings?: { [K in keyof AllSettings]?: Partial<AllSettings[K]> };
  /** Settings change callback */
  onSettingsChange?: (settings: AllSettings) => void;
}

/**
 * SettingsPanel component
 */
export const SettingsPanel: React.FC<SettingsPanelProps> = ({
  isOpen,
  onClose,
  initialSettings,
  onSettingsChange,
}) => {
  const { t } = useI18n();
  const [activeCategory, setActiveCategory] = useState<SettingsCategory>('page');
  const mergeSettings = useCallback((): AllSettings => {
    const base = { ...DEFAULT_SETTINGS };
    if (initialSettings) {
      for (const key of Object.keys(initialSettings) as (keyof AllSettings)[]) {
        if (initialSettings[key]) {
          (base as any)[key] = { ...base[key], ...initialSettings[key] };
        }
      }
    }
    return base;
  }, [initialSettings]);

  const [settings, setSettings] = useState<AllSettings>(mergeSettings);
  const [isDirty, setIsDirty] = useState(false);

  // Reset settings when panel opens
  useEffect(() => {
    if (isOpen) {
      setSettings(mergeSettings());
      setIsDirty(false);
    }
  }, [isOpen, mergeSettings]);

  const updateSettings = useCallback(
    <K extends keyof AllSettings>(category: K, updates: Partial<AllSettings[K]>) => {
      setSettings((prev) => ({
        ...prev,
        [category]: {
          ...prev[category],
          ...updates,
        },
      }));
      setIsDirty(true);
    },
    [],
  );

  const handleSave = useCallback(() => {
    onSettingsChange?.(settings);
    setIsDirty(false);
    onClose();
  }, [settings, onSettingsChange, onClose]);

  const handleReset = useCallback(() => {
    setSettings(mergeSettings());
    setIsDirty(false);
  }, [mergeSettings]);

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      {/* Backdrop */}
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />

      {/* Panel */}
      <div
        data-testid="settings-panel"
        className="relative flex max-h-[80vh] w-full max-w-3xl overflow-hidden rounded-xl bg-white shadow-2xl"
      >
        {/* Sidebar */}
        <div className="w-48 border-r border-gray-200 bg-gray-50 p-4">
          <h2
            data-testid="settings-panel-heading"
            className="mb-4 text-lg font-semibold text-gray-900"
          >
            {t('designer_settings.heading')}
          </h2>
          <nav className="space-y-1">
            {(Object.keys(SETTINGS_CATEGORY_INFO) as SettingsCategory[]).map((category) => {
              const info = SETTINGS_CATEGORY_INFO[category];
              const isActive = activeCategory === category;
              return (
                <button
                  key={category}
                  data-testid={`settings-category-${category}`}
                  onClick={() => setActiveCategory(category)}
                  className={`flex w-full items-center gap-3 rounded-lg px-3 py-2 text-left text-sm transition-colors ${
                    isActive ? 'bg-blue-100 text-blue-700' : 'text-gray-700 hover:bg-gray-100'
                  }`}
                >
                  <svg
                    className="h-5 w-5 flex-shrink-0"
                    fill="none"
                    viewBox="0 0 24 24"
                    stroke="currentColor"
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={1.5}
                      d={info.icon}
                    />
                  </svg>
                  <span>{t(info.labelKey)}</span>
                </button>
              );
            })}
          </nav>
        </div>

        {/* Content */}
        <div className="flex flex-1 flex-col">
          {/* Header */}
          <div className="flex items-center justify-between border-b border-gray-200 px-6 py-4">
            <div>
              <h3 className="text-lg font-medium text-gray-900">
                {t(SETTINGS_CATEGORY_INFO[activeCategory].labelKey)}
              </h3>
              <p className="text-sm text-gray-500">
                {t(SETTINGS_CATEGORY_INFO[activeCategory].descriptionKey)}
              </p>
            </div>
            <button aria-label={t('designer_settings.close')} onClick={onClose} className="rounded-lg p-1.5 text-gray-500 hover:bg-gray-100">
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

          {/* Settings content */}
          <div className="flex-1 overflow-y-auto p-6">
            {activeCategory === 'page' && (
              <PageSettingsForm
                settings={settings.page}
                onChange={(updates) => updateSettings('page', updates)}
              />
            )}
            {activeCategory === 'editor' && (
              <EditorSettingsForm
                settings={settings.editor}
                onChange={(updates) => updateSettings('editor', updates)}
              />
            )}
            {activeCategory === 'appearance' && (
              <AppearanceSettingsForm
                settings={settings.appearance}
                onChange={(updates) => updateSettings('appearance', updates)}
              />
            )}
            {activeCategory === 'export' && (
              <ExportSettingsForm
                settings={settings.export}
                onChange={(updates) => updateSettings('export', updates)}
              />
            )}
          </div>

          {/* Footer */}
          <div className="flex items-center justify-between border-t border-gray-200 bg-gray-50 px-6 py-4">
            <button
              onClick={handleReset}
              className="rounded-lg px-4 py-2 text-sm text-gray-700 hover:bg-gray-200"
            >
              {t('designer_settings.reset')}
            </button>
            <div className="flex items-center gap-3">
              <button
                onClick={onClose}
                className="rounded-lg px-4 py-2 text-sm text-gray-700 hover:bg-gray-200"
              >
                {t('designer_settings.cancel')}
              </button>
              <button
                data-testid="settings-panel-save"
                onClick={handleSave}
                disabled={!isDirty}
                className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {t('designer_settings.save')}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

/**
 * Page settings form
 */
const PageSettingsForm: React.FC<{
  settings: PageSettings;
  onChange: (updates: Partial<PageSettings>) => void;
}> = ({ settings, onChange }) => {
  const { t } = useI18n();
  return (
    <div className="space-y-6">
      <SettingsField label={t('designer_settings.title')}>
        <input
          type="text"
          value={settings.title}
          onChange={(e) => onChange({ title: e.target.value })}
          placeholder={t('designer_settings.title_placeholder')}
          className="w-full rounded-lg border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
        />
      </SettingsField>

      <SettingsField label={t('designer_settings.description')}>
        <textarea
          value={settings.description}
          onChange={(e) => onChange({ description: e.target.value })}
          placeholder={t('designer_settings.description_placeholder')}
          rows={3}
          className="w-full resize-none rounded-lg border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
        />
      </SettingsField>

      <SettingsField label={t('designer_settings.grid_columns')} hint={t('designer_settings.grid_columns_hint')}>
        <input
          type="number"
          value={settings.gridColumns}
          onChange={(e) => onChange({ gridColumns: parseInt(e.target.value) || 12 })}
          min={1}
          max={24}
          className="w-24 rounded-lg border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
        />
      </SettingsField>

      <SettingsField label={t('designer_settings.grid_gap')} hint={t('designer_settings.grid_gap_hint')}>
        <input
          type="number"
          value={settings.gridGap}
          onChange={(e) => onChange({ gridGap: parseInteger(e.target.value, 16) })}
          min={0}
          max={64}
          className="w-24 rounded-lg border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
        />
      </SettingsField>

      <SettingsField label={t('designer_settings.padding')} hint={t('designer_settings.padding_hint')}>
        <input
          type="number"
          value={settings.padding}
          onChange={(e) => onChange({ padding: parseInteger(e.target.value, 24) })}
          min={0}
          max={96}
          className="w-24 rounded-lg border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
        />
      </SettingsField>

      <SettingsToggle
        testId="settings-toggle-enableMultiView"
        label={t('designer_settings.multi_view')}
        description={t('designer_settings.multi_view_hint')}
        checked={settings.enableMultiView}
        onChange={(checked) => onChange({ enableMultiView: checked })}
      />
    </div>
  );
};

/**
 * Editor settings form
 */
const EditorSettingsForm: React.FC<{
  settings: EditorSettings;
  onChange: (updates: Partial<EditorSettings>) => void;
}> = ({ settings, onChange }) => {
  const { t } = useI18n();
  return (
    <div className="space-y-6">
      <SettingsToggle
        label={t('designer_settings.show_grid')}
        description={t('designer_settings.show_grid_hint')}
        checked={settings.showGrid}
        onChange={(checked) => onChange({ showGrid: checked })}
      />

      <SettingsToggle
        label={t('designer_settings.snap_grid')}
        description={t('designer_settings.snap_grid_hint')}
        checked={settings.snapToGrid}
        onChange={(checked) => onChange({ snapToGrid: checked })}
      />

      <SettingsField label={t('designer_settings.grid_size')} hint={t('designer_settings.grid_size_hint')}>
        <input
          type="number"
          value={settings.gridSize}
          onChange={(e) => onChange({ gridSize: parseInt(e.target.value) || 8 })}
          min={4}
          max={32}
          className="w-24 rounded-lg border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
        />
      </SettingsField>

      <SettingsToggle
        label={t('designer_settings.show_guides')}
        description={t('designer_settings.show_guides_hint')}
        checked={settings.showGuides}
        onChange={(checked) => onChange({ showGuides: checked })}
      />

      <SettingsToggle
        label={t('designer_settings.show_rulers')}
        description={t('designer_settings.show_rulers_hint')}
        checked={settings.showRulers}
        onChange={(checked) => onChange({ showRulers: checked })}
      />

      <SettingsToggle
        label={t('designer_settings.show_borders')}
        description={t('designer_settings.show_borders_hint')}
        checked={settings.showComponentBorders}
        onChange={(checked) => onChange({ showComponentBorders: checked })}
      />

      <div className="border-t border-gray-200 pt-6">
        <h4 className="mb-4 text-sm font-medium text-gray-900">{t('designer_settings.auto_save')}</h4>
        <div className="space-y-4">
          <SettingsToggle
            label={t('designer_settings.enable_auto_save')}
            description={t('designer_settings.enable_auto_save_hint')}
            checked={settings.enableAutoSave}
            onChange={(checked) => onChange({ enableAutoSave: checked })}
          />
          {settings.enableAutoSave && (
            <SettingsField label={t('designer_settings.save_interval')} hint={t('designer_settings.save_interval_hint')}>
              <input
                type="number"
                value={settings.autoSaveInterval}
                onChange={(e) => onChange({ autoSaveInterval: parseInt(e.target.value) || 30 })}
                min={10}
                max={300}
                className="w-24 rounded-lg border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
              />
            </SettingsField>
          )}
        </div>
      </div>

      <SettingsField label={t('designer_settings.zoom')} hint={t('designer_settings.zoom_hint')}>
        <div className="flex items-center gap-4">
          <input
            type="range"
            value={settings.zoomLevel}
            onChange={(e) => onChange({ zoomLevel: parseInt(e.target.value) })}
            min={25}
            max={200}
            step={25}
            className="flex-1"
          />
          <span className="w-12 text-sm text-gray-600">{settings.zoomLevel}%</span>
        </div>
      </SettingsField>
    </div>
  );
};

/**
 * Appearance settings form
 */
const AppearanceSettingsForm: React.FC<{
  settings: AppearanceSettings;
  onChange: (updates: Partial<AppearanceSettings>) => void;
}> = ({ settings, onChange }) => {
  const { t } = useI18n();
  return (
    <div className="space-y-6">
      <SettingsField label={t('designer_settings.theme')}>
        <div className="flex items-center gap-3">
          {(['light', 'dark', 'system'] as const).map((theme) => (
            <button
              key={theme}
              onClick={() => onChange({ theme })}
              className={`rounded-lg border px-4 py-2 text-sm ${
                settings.theme === theme
                  ? 'border-blue-500 bg-blue-50 text-blue-700'
                  : 'border-gray-300 text-gray-700 hover:bg-gray-50'
              }`}
            >
              {theme === 'light' && t('designer_settings.theme_light')}
              {theme === 'dark' && t('designer_settings.theme_dark')}
              {theme === 'system' && t('designer_settings.theme_system')}
            </button>
          ))}
        </div>
      </SettingsField>

      <SettingsField label={t('designer_settings.primary_color')}>
        <div className="flex items-center gap-3">
          <input
            type="color"
            value={settings.primaryColor}
            onChange={(e) => onChange({ primaryColor: e.target.value })}
            className="h-10 w-10 cursor-pointer rounded-lg border border-gray-300"
          />
          <input
            type="text"
            value={settings.primaryColor}
            onChange={(e) => onChange({ primaryColor: e.target.value })}
            className="w-28 rounded-lg border border-gray-300 px-3 py-2 font-mono text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          />
        </div>
      </SettingsField>

      <SettingsField label={t('designer_settings.background')}>
        <div className="grid grid-cols-4 gap-2">
          {(['white', 'light', 'dots', 'grid'] as const).map((bg) => (
            <button
              key={bg}
              onClick={() => onChange({ canvasBackground: bg })}
              className={`rounded-lg border p-3 text-center ${
                settings.canvasBackground === bg
                  ? 'border-blue-500 bg-blue-50'
                  : 'border-gray-200 hover:border-gray-300'
              }`}
            >
              <div
                className={`mb-2 h-8 w-full rounded ${
                  bg === 'white'
                    ? 'border border-gray-200 bg-white'
                    : bg === 'light'
                      ? 'bg-gray-100'
                      : bg === 'dots'
                        ? 'bg-white'
                        : 'bg-white'
                }`}
                style={{
                  backgroundImage:
                    bg === 'dots'
                      ? 'radial-gradient(circle, #d1d5db 1px, transparent 1px)'
                      : bg === 'grid'
                        ? 'linear-gradient(#e5e7eb 1px, transparent 1px), linear-gradient(90deg, #e5e7eb 1px, transparent 1px)'
                        : undefined,
                  backgroundSize:
                    bg === 'dots' ? '16px 16px' : bg === 'grid' ? '20px 20px' : undefined,
                }}
              />
              <span className="text-xs text-gray-600 capitalize">
                {bg === 'white' && t('designer_settings.background_white')}
                {bg === 'light' && t('designer_settings.background_light')}
                {bg === 'dots' && t('designer_settings.background_dots')}
                {bg === 'grid' && t('designer_settings.background_grid')}
              </span>
            </button>
          ))}
        </div>
      </SettingsField>

      <SettingsField label={t('designer_settings.sidebar')}>
        <div className="flex items-center gap-3">
          {(['left', 'right'] as const).map((pos) => (
            <button
              key={pos}
              onClick={() => onChange({ sidebarPosition: pos })}
              className={`rounded-lg border px-4 py-2 text-sm ${
                settings.sidebarPosition === pos
                  ? 'border-blue-500 bg-blue-50 text-blue-700'
                  : 'border-gray-300 text-gray-700 hover:bg-gray-50'
              }`}
            >
              {pos === 'left' ? t('designer_settings.sidebar_left') : t('designer_settings.sidebar_right')}
            </button>
          ))}
        </div>
      </SettingsField>

      <SettingsField label={t('designer_settings.panel_width')} hint={t('designer_settings.panel_width_hint')}>
        <div className="flex items-center gap-4">
          <input
            type="range"
            value={settings.panelWidth}
            onChange={(e) => onChange({ panelWidth: parseInt(e.target.value) })}
            min={200}
            max={400}
            step={20}
            className="flex-1"
          />
          <span className="w-16 text-sm text-gray-600">{settings.panelWidth}px</span>
        </div>
      </SettingsField>
    </div>
  );
};

/**
 * Export settings form
 */
const ExportSettingsForm: React.FC<{
  settings: ExportSettings;
  onChange: (updates: Partial<ExportSettings>) => void;
}> = ({ settings, onChange }) => {
  const { t } = useI18n();
  return (
    <div className="space-y-6">
      <SettingsField label={t('designer_settings.format')}>
        <div className="flex items-center gap-3">
          {(['json', 'yaml'] as const).map((format) => (
            <button
              key={format}
              onClick={() => onChange({ exportFormat: format })}
              className={`rounded-lg border px-4 py-2 text-sm uppercase ${
                settings.exportFormat === format
                  ? 'border-blue-500 bg-blue-50 text-blue-700'
                  : 'border-gray-300 text-gray-700 hover:bg-gray-50'
              }`}
            >
              {format}
            </button>
          ))}
        </div>
      </SettingsField>

      <SettingsToggle
        label={t('designer_settings.metadata')}
        description={t('designer_settings.metadata_hint')}
        checked={settings.includeMetadata}
        onChange={(checked) => onChange({ includeMetadata: checked })}
      />

      <SettingsToggle
        label={t('designer_settings.pretty')}
        description={t('designer_settings.pretty_hint')}
        checked={settings.prettyPrint}
        onChange={(checked) => onChange({ prettyPrint: checked })}
      />

      <SettingsToggle
        label={t('designer_settings.history')}
        description={t('designer_settings.history_hint')}
        checked={settings.includeVersionHistory}
        onChange={(checked) => onChange({ includeVersionHistory: checked })}
      />
    </div>
  );
};

/**
 * Settings field wrapper
 */
const SettingsField: React.FC<{
  label: string;
  hint?: string;
  children: React.ReactNode;
}> = ({ label, hint, children }) => {
  return (
    <fieldset className="min-w-0">
      <legend className="mb-1 block text-sm font-medium text-gray-700">{label}</legend>
      {hint && <p className="mb-2 text-xs text-gray-500">{hint}</p>}
      {React.isValidElement(children) && ['input', 'textarea'].includes(String(children.type))
        ? React.cloneElement(children as React.ReactElement<{ 'aria-label'?: string }>, { 'aria-label': label })
        : children}
    </fieldset>
  );
};

/**
 * Settings toggle
 */
const SettingsToggle: React.FC<{
  label: string;
  description: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
  testId?: string;
}> = ({ label, description, checked, onChange, testId }) => {
  return (
    <div className="flex items-center justify-between">
      <div>
        <p className="text-sm font-medium text-gray-900">{label}</p>
        <p className="text-xs text-gray-500">{description}</p>
      </div>
      <button
        data-testid={testId}
        role="switch"
        aria-label={label}
        aria-checked={checked}
        onClick={() => onChange(!checked)}
        className={`relative h-6 w-11 rounded-full transition-colors ${
          checked ? 'bg-blue-600' : 'bg-gray-200'
        }`}
      >
        <span
          className={`absolute top-1 left-1 h-4 w-4 rounded-full bg-white shadow transition-transform ${
            checked ? 'translate-x-5' : 'translate-x-0'
          }`}
        />
      </button>
    </div>
  );
};

export default SettingsPanel;
