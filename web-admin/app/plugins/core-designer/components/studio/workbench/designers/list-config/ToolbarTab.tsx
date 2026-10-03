import React, { useMemo, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import type { ModelCapabilities } from '~/shared/hooks/useModelCapabilities';
import { SchemaBlockConfigPanel } from '~/shared/designer/SchemaBlockConfigPanel';
import { buildCustomButtonSchemas } from './schema';
import type { ListViewModel, CustomButton, ToolbarPresetKey } from './mapper';

export interface ToolbarTabProps {
  vm: ListViewModel;
  setVm: (next: ListViewModel) => void;
  capabilities: ModelCapabilities | undefined;
  readonly?: boolean;
  loading?: boolean;
  capabilityError?: Error;
}

function sectionCardClasses(extra?: string): string {
  return ['rounded-3xl border border-slate-200 bg-slate-50/70 p-5', extra]
    .filter(Boolean)
    .join(' ');
}

interface PresetDescriptor {
  key: ToolbarPresetKey;
  label: string;
  /** Capability gate: preset is disabled when this capability is false. */
  capability?: keyof ModelCapabilities;
  description: string;
}

function buildPresets(t: (key: string) => string): PresetDescriptor[] {
  return [
    {
      key: 'create',
      label: t('list_editor.create'),
      capability: 'create',
      description: t('list_editor.preset_supported'),
    },
    {
      key: 'refresh',
      label: t('list_editor.refresh'),
      description: t('list_editor.refresh_description'),
    },
    {
      key: 'export',
      label: t('list_editor.export'),
      capability: 'export',
      description: t('list_editor.preset_supported'),
    },
    {
      key: 'bulkDelete',
      label: t('list_editor.bulk_delete'),
      capability: 'bulkDelete',
      description: t('list_editor.preset_supported'),
    },
  ];
}

export const ToolbarTab: React.FC<ToolbarTabProps> = ({
  vm,
  setVm,
  capabilities,
  readonly,
  loading,
  capabilityError,
}) => {
  const { t } = useI18n();
  const schemas = useMemo(() => buildCustomButtonSchemas(t), [t]);
  const presets = useMemo(() => buildPresets(t), [t]);
  const [selectedBtnIdx, setSelectedBtnIdx] = useState<number | null>(null);

  if (!capabilities && loading) {
    return (
      <div className="space-y-4" data-testid="toolbar-tab">
        <div className={sectionCardClasses()}>
          <div className="text-xs font-semibold tracking-[0.16em] text-slate-400 uppercase">
            {t('list_editor.toolbar_configuration')}
          </div>
          <div className="mt-2 text-sm text-slate-500">{t('list_editor.toolbar_loading')}</div>
        </div>
      </div>
    );
  }

  const activePresets = new Set(vm.toolbar.presets);

  const togglePreset = (key: ToolbarPresetKey, allowed: boolean) => {
    if (readonly || !allowed) return;
    const next = activePresets.has(key)
      ? vm.toolbar.presets.filter((p) => p !== key)
      : [...vm.toolbar.presets, key];
    setVm({ ...vm, toolbar: { ...vm.toolbar, presets: next } });
  };

  const addCustomButton = () => {
    if (readonly) return;
    const next: CustomButton = { label: '', command: '', actionKind: 'command' };
    const nextButtons = [...vm.toolbar.customButtons, next];
    setVm({ ...vm, toolbar: { ...vm.toolbar, customButtons: nextButtons } });
    setSelectedBtnIdx(nextButtons.length - 1);
  };

  const removeCustomButton = (idx: number) => {
    if (readonly) return;
    const nextButtons = vm.toolbar.customButtons.filter((_, i) => i !== idx);
    setVm({ ...vm, toolbar: { ...vm.toolbar, customButtons: nextButtons } });
    setSelectedBtnIdx(null);
  };

  const updateCustomButton = (idx: number, patch: Partial<CustomButton>) => {
    const nextButtons = vm.toolbar.customButtons.map((b, i) =>
      i === idx ? { ...b, ...patch } : b,
    );
    setVm({ ...vm, toolbar: { ...vm.toolbar, customButtons: nextButtons } });
  };

  const selected = selectedBtnIdx !== null ? vm.toolbar.customButtons[selectedBtnIdx] : null;
  const activeActionCount = vm.toolbar.presets.length + vm.toolbar.customButtons.length;

  return (
    <div className="space-y-5" data-testid="toolbar-tab">
      <section className={sectionCardClasses()}>
        <div className="flex flex-col gap-4 border-b border-slate-200 pb-4 lg:flex-row lg:items-end lg:justify-between">
          <div>
            <div className="text-xs font-semibold tracking-[0.16em] text-slate-400 uppercase">
              {t('list_editor.preset_actions')}
            </div>
            <h2 className="mt-2 text-lg font-semibold text-slate-900">
              {t('list_editor.toolbar_heading')}
            </h2>
            <p className="mt-2 text-sm leading-6 text-slate-500">
              {t('list_editor.toolbar_description')}
            </p>
          </div>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
            <div className="rounded-2xl border border-slate-200 bg-white px-4 py-3">
              <div className="text-[11px] tracking-[0.14em] text-slate-400 uppercase">
                {t('list_editor.enabled_presets')}
              </div>
              <div className="mt-2 text-xl font-semibold text-slate-900">
                {vm.toolbar.presets.length}
              </div>
            </div>
            <div className="rounded-2xl border border-slate-200 bg-white px-4 py-3">
              <div className="text-[11px] tracking-[0.14em] text-slate-400 uppercase">
                {t('list_editor.custom_buttons')}
              </div>
              <div className="mt-2 text-xl font-semibold text-slate-900">
                {vm.toolbar.customButtons.length}
              </div>
            </div>
            <div className="rounded-2xl border border-slate-200 bg-white px-4 py-3">
              <div className="text-[11px] tracking-[0.14em] text-slate-400 uppercase">
                {t('list_editor.total_actions')}
              </div>
              <div className="mt-2 text-xl font-semibold text-slate-900">{activeActionCount}</div>
            </div>
          </div>
        </div>
        <div className="space-y-2">
          {capabilityError && (
            <div className="rounded-2xl border border-amber-200 bg-amber-50 px-4 py-4 text-sm text-amber-800">
              {t('list_editor.toolbar_capability_error')}
            </div>
          )}
          {presets.map((preset) => {
            const allowed = preset.capability ? !!capabilities?.[preset.capability] : true;
            const active = activePresets.has(preset.key);
            return (
              <label
                key={preset.key}
                className={`flex items-center gap-3 rounded-2xl border bg-white px-4 py-3 text-sm ${
                  allowed ? 'border-slate-200' : 'border-slate-100 opacity-60'
                }`}
              >
                <input
                  type="checkbox"
                  checked={active}
                  onChange={() => togglePreset(preset.key, allowed)}
                  disabled={readonly || !allowed}
                  data-testid={`toolbar-preset-${preset.key}`}
                />
                <span className="min-w-0 flex-1">
                  <span className="block font-medium text-slate-800">{preset.label}</span>
                  <span className="mt-1 block text-xs text-slate-500">
                    {capabilityError && preset.capability
                      ? t('list_editor.preset_capability_error')
                      : allowed
                        ? preset.description
                        : t('list_editor.action_unavailable', { action: preset.label })}
                  </span>
                </span>
                <span
                  className={`rounded-full px-2.5 py-1 text-[11px] font-medium ${
                    active ? 'bg-blue-100 text-blue-700' : 'bg-slate-100 text-slate-500'
                  }`}
                >
                  {active ? t('list_editor.enabled') : t('list_editor.disabled')}
                </span>
              </label>
            );
          })}
        </div>
      </section>

      <section className={sectionCardClasses()}>
        <div className="mb-4 flex flex-col gap-3 border-b border-slate-200 pb-4 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <div className="text-xs font-semibold tracking-[0.16em] text-slate-400 uppercase">
              {t('list_editor.custom_actions')}
            </div>
            <h3 className="mt-2 text-base font-semibold text-slate-900">
              {t('list_editor.custom_buttons')}
            </h3>
            <p className="mt-2 text-sm text-slate-500">{t('list_editor.custom_actions_hint')}</p>
          </div>
          <button
            type="button"
            onClick={addCustomButton}
            disabled={readonly}
            className="rounded-2xl bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-30"
            data-testid="toolbar-add-custom-button"
          >
            {t('list_editor.add_button')}
          </button>
        </div>

        {vm.toolbar.customButtons.length === 0 ? (
          <div className="rounded-2xl border border-dashed border-slate-300 bg-white px-4 py-6 text-sm text-slate-400">
            {t('list_editor.custom_buttons_empty')}
          </div>
        ) : (
          <div className="space-y-3">
            <div className="flex flex-wrap gap-2">
              <span className="rounded-full bg-white px-3 py-1 text-xs font-medium text-slate-500">
                {t('list_editor.toolbar_limit_hint')}
              </span>
              <span className="rounded-full bg-white px-3 py-1 text-xs font-medium text-slate-500">
                {t('list_editor.action_label_hint')}
              </span>
              <span className="rounded-full bg-white px-3 py-1 text-xs font-medium text-slate-500">
                {t('list_editor.duplicate_action_hint')}
              </span>
            </div>
            <ol className="space-y-2">
              {vm.toolbar.customButtons.map((b, i) => (
                <li
                  key={i}
                  className={`rounded-2xl border bg-white px-4 py-4 text-sm transition ${
                    selectedBtnIdx === i ? 'border-blue-200 bg-blue-50/70' : 'border-slate-200'
                  }`}
                >
                  <div className="flex items-start justify-between gap-3">
                    <button
                      type="button"
                      className="flex min-w-0 flex-1 items-start gap-3 text-left"
                      onClick={() => setSelectedBtnIdx(selectedBtnIdx === i ? null : i)}
                      data-testid={`toolbar-custom-item-${i}`}
                    >
                      <span className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-slate-100 text-xs font-semibold text-slate-500">
                        {b.icon || `B${i + 1}`}
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block truncate font-medium text-slate-800">
                          {b.label || t('list_editor.unnamed')}
                        </span>
                        <span className="mt-1 block truncate text-xs text-slate-500">
                          {b.actionKind === 'refresh'
                            ? t('list_editor.refresh_target', {
                                source: b.targetDataSource || t('list_editor.no_data_source'),
                              })
                            : b.command || t('list_editor.command_unbound')}
                        </span>
                        <span className="mt-3 flex flex-wrap gap-2">
                          <span className="rounded-full bg-slate-100 px-2.5 py-1 text-[11px] text-slate-600">
                            {b.icon
                              ? t('list_editor.icon_name', { icon: b.icon })
                              : t('list_editor.no_icon')}
                          </span>
                          <span
                            className={`rounded-full px-2.5 py-1 text-[11px] ${
                              b.requiresSelection
                                ? 'bg-amber-50 text-amber-700'
                                : 'bg-emerald-50 text-emerald-700'
                            }`}
                          >
                            {b.requiresSelection
                              ? t('list_editor.requires_selection')
                              : t('list_editor.page_action')}
                          </span>
                        </span>
                      </span>
                    </button>
                    <button
                      type="button"
                      onClick={() => removeCustomButton(i)}
                      disabled={readonly}
                      className="ml-3 rounded-xl border border-red-200 px-3 py-1.5 text-xs text-red-600 disabled:opacity-30"
                      data-testid={`toolbar-custom-remove-${i}`}
                    >
                      {t('list_editor.delete')}
                    </button>
                  </div>
                  <div className="mt-4 flex flex-wrap gap-2 border-t border-slate-100 pt-3">
                    <button
                      type="button"
                      onClick={() =>
                        updateCustomButton(i, {
                          requiresSelection: !b.requiresSelection,
                        })
                      }
                      disabled={readonly}
                      className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1 text-[11px] font-medium text-slate-600 disabled:opacity-30"
                    >
                      {b.requiresSelection
                        ? t('list_editor.switch_page_action')
                        : t('list_editor.switch_row_action')}
                    </button>
                    <button
                      type="button"
                      onClick={() =>
                        updateCustomButton(i, {
                          icon: nextButtonIcon(b.icon),
                        })
                      }
                      disabled={readonly}
                      className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1 text-[11px] font-medium text-slate-600 disabled:opacity-30"
                    >
                      {t('list_editor.icon_prefix')}
                      {b.icon || t('list_editor.none')}
                    </button>
                  </div>
                </li>
              ))}
            </ol>
          </div>
        )}
      </section>

      <section className={sectionCardClasses()} data-testid="toolbar-custom-editor">
        <div className="mb-4 border-b border-slate-200 pb-4">
          <div className="text-xs font-semibold tracking-[0.16em] text-slate-400 uppercase">
            {t('list_editor.action_properties')}
          </div>
          <h3 className="mt-2 text-base font-semibold text-slate-900">
            {selected
              ? t('list_editor.button_properties_for', {
                  label: selected.label || t('list_editor.unnamed_button'),
                })
              : t('list_editor.button_properties')}
          </h3>
          <p className="mt-2 text-sm text-slate-500">{t('list_editor.button_properties_hint')}</p>
        </div>
        {selected && selectedBtnIdx !== null ? (
          <SchemaBlockConfigPanel
            schemas={schemas}
            value={selected as unknown as Record<string, unknown>}
            onChange={(next) => updateCustomButton(selectedBtnIdx, next as Partial<CustomButton>)}
            readonly={readonly}
          />
        ) : (
          <div className="rounded-2xl border border-dashed border-slate-300 bg-white px-4 py-6 text-sm text-slate-400">
            {t('list_editor.button_properties_empty')}
          </div>
        )}
      </section>
    </div>
  );
};

function nextButtonIcon(icon?: string): string | undefined {
  const order = [undefined, 'plus', 'download', 'bolt'];
  const current = order.indexOf(icon);
  return order[(current + 1) % order.length];
}
