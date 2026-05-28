/**
 * B2b batch3 — HookConfigSection
 *
 * Extracted from bpmn-designer/components/property-editors/shared.tsx (lines
 * 332-627). Owns the 3 action-type sub-configs (http_callback / script /
 * command) plus the per-hook envelope (hookType / failStrategy / async /
 * enabled / executionOrder).
 *
 * Hosting editor wires this section by translating G2 NodePropertyEditorProps
 * `(config, onChange)` into a (hooks, onChange(hooks)) prop pair.
 *
 * data-testids and i18n keys are byte-equivalent to legacy shared.tsx so
 * existing E2E selectors continue to match.
 */

import { useState } from 'react';
import type { NodeHookEntry } from '~/plugins/core-designer/components/bpmn-designer/types';
import { useI18n } from '~/contexts/I18nContext';

// ---------------------------------------------------------------------------
// Action-type sub-configs (private to this module)
// ---------------------------------------------------------------------------

function HttpCallbackConfig({
  config,
  onChange,
}: {
  config: Record<string, unknown>;
  onChange: (config: Record<string, unknown>) => void;
}) {
  const { t } = useI18n();
  return (
    <div className="mt-2 space-y-2 rounded border border-gray-200 bg-white p-2">
      <div>
        <label className="mb-0.5 block text-xs text-gray-500">{t('bpmn.hook.httpUrl')}</label>
        <input
          type="text"
          value={(config.url as string) || ''}
          onChange={(e) => onChange({ ...config, url: e.target.value })}
          data-testid="hook-http-url"
          className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
          placeholder="https://example.com/webhook"
        />
      </div>
      <div>
        <label className="mb-0.5 block text-xs text-gray-500">{t('bpmn.hook.httpMethod')}</label>
        <select
          value={(config.method as string) || 'POST'}
          onChange={(e) => onChange({ ...config, method: e.target.value })}
          className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
        >
          <option value="POST">POST</option>
          <option value="GET">GET</option>
          <option value="PUT">PUT</option>
        </select>
      </div>
      <div>
        <label className="mb-0.5 block text-xs text-gray-500">{t('bpmn.hook.httpHeaders')}</label>
        <textarea
          value={(config.headers as string) || ''}
          onChange={(e) => onChange({ ...config, headers: e.target.value })}
          className="w-full rounded border border-gray-300 px-2 py-1 font-mono text-xs"
          rows={2}
          placeholder='{"Content-Type": "application/json"}'
        />
      </div>
    </div>
  );
}

function ScriptActionConfig({
  config,
  onChange,
}: {
  config: Record<string, unknown>;
  onChange: (config: Record<string, unknown>) => void;
}) {
  const { t } = useI18n();
  return (
    <div className="mt-2 space-y-2 rounded border border-gray-200 bg-white p-2">
      <div>
        <label className="mb-0.5 block text-xs text-gray-500">
          {t('bpmn.hook.scriptLanguage')}
        </label>
        <select
          value={(config.language as string) || 'javascript'}
          onChange={(e) => onChange({ ...config, language: e.target.value })}
          className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
        >
          <option value="javascript">JavaScript</option>
          <option value="groovy">Groovy</option>
        </select>
      </div>
      <div>
        <label className="mb-0.5 block text-xs text-gray-500">{t('bpmn.hook.scriptContent')}</label>
        <textarea
          value={(config.script as string) || ''}
          onChange={(e) => onChange({ ...config, script: e.target.value })}
          className="w-full rounded border border-gray-300 px-2 py-1 font-mono text-xs"
          rows={4}
          placeholder="// your script here"
        />
      </div>
    </div>
  );
}

function CommandActionConfig({
  config,
  onChange,
}: {
  config: Record<string, unknown>;
  onChange: (config: Record<string, unknown>) => void;
}) {
  const { t } = useI18n();
  return (
    <div className="mt-2 space-y-2 rounded border border-gray-200 bg-white p-2">
      <div>
        <label className="mb-0.5 block text-xs text-gray-500">{t('bpmn.hook.commandCode')}</label>
        <input
          type="text"
          value={(config.commandCode as string) || ''}
          onChange={(e) => onChange({ ...config, commandCode: e.target.value })}
          data-testid="hook-command-code"
          className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
          placeholder="namespace:command_code"
        />
      </div>
      <div>
        <label className="mb-0.5 block text-xs text-gray-500">{t('bpmn.hook.commandParams')}</label>
        <textarea
          value={(config.params as string) || ''}
          onChange={(e) => onChange({ ...config, params: e.target.value })}
          className="w-full rounded border border-gray-300 px-2 py-1 font-mono text-xs"
          rows={2}
          placeholder='{"key": "${variable}"}'
        />
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// HookConfigSection
// ---------------------------------------------------------------------------

export interface HookConfigSectionProps {
  hooks: NodeHookEntry[];
  onChange: (hooks: NodeHookEntry[]) => void;
}

export function HookConfigSection({ hooks, onChange }: HookConfigSectionProps) {
  const { t } = useI18n();
  const [expanded, setExpanded] = useState(hooks.length > 0);

  const addHook = () => {
    onChange([
      ...hooks,
      {
        hookType: 'pre_execute',
        executionOrder: hooks.length,
        hookConfig: { actionType: 'http_callback' },
        failStrategy: 'block',
        async: false,
        enabled: true,
      },
    ]);
  };

  const removeHook = (index: number) => {
    onChange(hooks.filter((_, i) => i !== index));
  };

  const updateHook = (index: number, field: keyof NodeHookEntry, value: any) => {
    const updated = [...hooks];
    updated[index] = { ...updated[index], [field]: value };
    onChange(updated);
  };

  const updateHookConfig = (index: number, config: Record<string, unknown>) => {
    const updated = [...hooks];
    updated[index] = { ...updated[index], hookConfig: config };
    onChange(updated);
  };

  const getActionType = (hook: NodeHookEntry): string =>
    (hook.hookConfig?.actionType as string) || 'http_callback';

  return (
    <div className="mb-4 rounded-md border border-gray-200" data-testid="bpm-sdk-hook-section">
      <button
        type="button"
        onClick={() => setExpanded(!expanded)}
        data-testid="hook-section-toggle"
        className="flex w-full items-center justify-between px-3 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
      >
        <span>
          {t('bpmn.hook.title')} ({hooks.length})
        </span>
        <span className="text-gray-400">{expanded ? '▾' : '▸'}</span>
      </button>
      {expanded && (
        <div className="px-3 pb-3" data-testid="hook-section-body">
          {hooks.map((hook, index) => (
            <div
              key={index}
              className="mb-3 rounded border border-gray-100 bg-gray-50 p-2"
              data-testid={`hook-entry-${index}`}
            >
              <div className="mb-2 flex items-center justify-between">
                <span className="text-xs font-medium text-gray-500">
                  {t('bpmn.hook.hookNumber')} #{index + 1}
                </span>
                <button
                  type="button"
                  onClick={() => removeHook(index)}
                  className="text-xs text-red-500 hover:text-red-700"
                >
                  {t('bpmn.common.remove')}
                </button>
              </div>
              <div className="mb-2">
                <label className="mb-1 block text-xs text-gray-600">
                  {t('bpmn.hook.hookType')}
                </label>
                <select
                  value={hook.hookType}
                  onChange={(e) => updateHook(index, 'hookType', e.target.value)}
                  data-testid={`hook-type-${index}`}
                  className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
                >
                  <option value="pre_execute">{t('bpmn.hook.typePreExecute')}</option>
                  <option value="post_execute">{t('bpmn.hook.typePostExecute')}</option>
                  <option value="pre_complete">{t('bpmn.hook.typePreComplete')}</option>
                  <option value="post_complete">{t('bpmn.hook.typePostComplete')}</option>
                </select>
              </div>
              <div className="mb-2">
                <label className="mb-1 block text-xs text-gray-600">
                  {t('bpmn.hook.actionType')}
                </label>
                <select
                  value={getActionType(hook)}
                  onChange={(e) => updateHookConfig(index, { actionType: e.target.value })}
                  data-testid={`hook-action-type-${index}`}
                  className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
                >
                  <option value="http_callback">{t('bpmn.hook.actionHttpCallback')}</option>
                  <option value="script">{t('bpmn.hook.actionScript')}</option>
                  <option value="command">{t('bpmn.hook.actionCommand')}</option>
                </select>
              </div>

              {/* Action-type-specific config */}
              {getActionType(hook) === 'http_callback' && (
                <HttpCallbackConfig
                  config={hook.hookConfig}
                  onChange={(config) =>
                    updateHookConfig(index, { ...config, actionType: 'http_callback' })
                  }
                />
              )}
              {getActionType(hook) === 'script' && (
                <ScriptActionConfig
                  config={hook.hookConfig}
                  onChange={(config) =>
                    updateHookConfig(index, { ...config, actionType: 'script' })
                  }
                />
              )}
              {getActionType(hook) === 'command' && (
                <CommandActionConfig
                  config={hook.hookConfig}
                  onChange={(config) =>
                    updateHookConfig(index, { ...config, actionType: 'command' })
                  }
                />
              )}

              <div className="mb-2">
                <label className="mb-1 block text-xs text-gray-600">
                  {t('bpmn.hook.failStrategy')}
                </label>
                <select
                  value={hook.failStrategy || 'block'}
                  onChange={(e) => updateHook(index, 'failStrategy', e.target.value)}
                  className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
                >
                  <option value="block">{t('bpmn.hook.failBlock')}</option>
                  <option value="ignore">{t('bpmn.hook.failIgnore')}</option>
                  <option value="retry">{t('bpmn.hook.failRetry')}</option>
                </select>
              </div>
              <div className="mb-2 flex items-center gap-4">
                <label className="flex items-center">
                  <input
                    type="checkbox"
                    checked={hook.async || false}
                    onChange={(e) => updateHook(index, 'async', e.target.checked)}
                    className="mr-1"
                  />
                  <span className="text-xs text-gray-600">{t('bpmn.hook.async')}</span>
                </label>
                <label className="flex items-center">
                  <input
                    type="checkbox"
                    checked={hook.enabled !== false}
                    onChange={(e) => updateHook(index, 'enabled', e.target.checked)}
                    className="mr-1"
                  />
                  <span className="text-xs text-gray-600">{t('bpmn.hook.enabled')}</span>
                </label>
              </div>
              <div className="mb-2">
                <label className="mb-1 block text-xs text-gray-600">
                  {t('bpmn.hook.executionOrder')}
                </label>
                <input
                  type="number"
                  value={hook.executionOrder ?? index}
                  onChange={(e) =>
                    updateHook(index, 'executionOrder', parseInt(e.target.value) || 0)
                  }
                  className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
                  min="0"
                />
              </div>
            </div>
          ))}
          <button
            type="button"
            onClick={addHook}
            data-testid="hook-add-btn"
            className="w-full rounded border border-dashed border-blue-300 py-1 text-sm text-blue-600 hover:bg-blue-50 hover:text-blue-800"
          >
            {t('bpmn.hook.addHook')}
          </button>
        </div>
      )}
    </div>
  );
}
