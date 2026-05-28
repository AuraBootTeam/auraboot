/**
 * B2b — first batch of BPMN property editors ported onto flow-designer-sdk
 * via the G2 NodePropertyEditorProps contract.
 *
 * Ported editors:
 *   - StartEventEditor      (was EventEditor.tsx#StartEventEditor)
 *   - EndEventEditor        (was EventEditor.tsx#EndEventEditor)
 *   - ParallelGatewayEditor (was ParallelGatewayEditor.tsx)
 *   - ServiceTaskEditor     (was ServiceTaskEditor.tsx)
 *
 * Contract diff vs. legacy: the legacy editors took
 *   { config, onChange(full config) }
 * G2 NodePropertyEditorProps gives us
 *   { nodeId, config, onChange(patch) }
 * so each handler emits a partial patch (`{ field: value }`) rather than the
 * full merged config. The store-side merge already happens in
 * useFlowStore.updateNode, so this is the more efficient shape anyway.
 *
 * NOT ported in batch1 (deferred to later batches):
 *   - ServiceTaskEditor.HookConfigSection (depends on shared.tsx 627 LOC —
 *     port as part of batch covering shared.tsx)
 */

import { useI18n } from '~/contexts/I18nContext';
import type { NodePropertyEditorProps } from '~/plugins/core-designer/components/flow-designer-sdk';
import type {
  StartEventConfig,
  EndEventConfig,
  ParallelGatewayConfig,
  ServiceTaskConfig,
} from '~/plugins/core-designer/components/bpmn-designer/types';

export function StartEventEditor({ config, onChange, readOnly }: NodePropertyEditorProps) {
  const { t } = useI18n();
  const c = (config ?? {}) as unknown as StartEventConfig;

  return (
    <>
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {t('bpmn.common.description')}
        </label>
        <textarea
          value={c.description ?? ''}
          onChange={(e) => onChange({ description: e.target.value })}
          disabled={readOnly}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          rows={2}
          data-testid="bpm-sdk-start-description"
        />
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {t('bpmn.prop.startevent.initiator')}
        </label>
        <input
          type="text"
          value={c.initiator ?? 'initiator'}
          onChange={(e) => onChange({ initiator: e.target.value })}
          disabled={readOnly}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          data-testid="bpm-sdk-start-initiator"
        />
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {t('bpmn.prop.startevent.formKey')}
        </label>
        <input
          type="text"
          value={c.formKey ?? ''}
          onChange={(e) => onChange({ formKey: e.target.value })}
          disabled={readOnly}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          placeholder={t('bpmn.prop.startevent.formKeyPlaceholder')}
          data-testid="bpm-sdk-start-formkey"
        />
      </div>
    </>
  );
}

export function EndEventEditor({ config, onChange, readOnly }: NodePropertyEditorProps) {
  const { t } = useI18n();
  const c = (config ?? {}) as unknown as EndEventConfig;

  return (
    <>
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {t('bpmn.common.description')}
        </label>
        <textarea
          value={c.description ?? ''}
          onChange={(e) => onChange({ description: e.target.value })}
          disabled={readOnly}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          rows={2}
          data-testid="bpm-sdk-end-description"
        />
      </div>

      <div className="mb-4">
        <label className="flex items-center">
          <input
            type="checkbox"
            checked={c.terminateAll ?? false}
            onChange={(e) => onChange({ terminateAll: e.target.checked })}
            disabled={readOnly}
            className="mr-2"
            data-testid="bpm-sdk-end-terminate-all"
          />
          <span className="text-sm font-medium text-gray-700">
            {t('bpmn.prop.endevent.terminateAll')}
          </span>
        </label>
      </div>
    </>
  );
}

export function ParallelGatewayEditor({ config, onChange, readOnly }: NodePropertyEditorProps) {
  const { t } = useI18n();
  const c = (config ?? {}) as unknown as ParallelGatewayConfig;

  return (
    <>
      <div className="mb-4 rounded-md border border-blue-100 bg-blue-50 px-3 py-2">
        <p className="text-sm text-blue-700">{t('bpmn.gateway.parallelInfo')}</p>
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {t('bpmn.common.description')}
        </label>
        <textarea
          value={c.description ?? ''}
          onChange={(e) => onChange({ description: e.target.value })}
          disabled={readOnly}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
          rows={2}
          placeholder={t('bpmn.gateway.parallelDescPlaceholder')}
          data-testid="bpm-sdk-parallel-description"
        />
      </div>
    </>
  );
}

export function ServiceTaskEditor({ config, onChange, readOnly }: NodePropertyEditorProps) {
  const { t } = useI18n();
  const c = (config ?? {}) as unknown as ServiceTaskConfig;

  return (
    <>
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {t('bpmn.common.description')}
        </label>
        <textarea
          value={c.description ?? ''}
          onChange={(e) => onChange({ description: e.target.value })}
          disabled={readOnly}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          rows={2}
          data-testid="bpm-sdk-svc-description"
        />
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {t('bpmn.prop.servicetask.serviceType')}
        </label>
        <select
          value={c.serviceType ?? 'http'}
          onChange={(e) =>
            onChange({ serviceType: e.target.value as ServiceTaskConfig['serviceType'] })
          }
          disabled={readOnly}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          data-testid="bpm-sdk-svc-service-type"
        >
          <option value="http">{t('bpmn.prop.servicetask.typeHttp')}</option>
          <option value="java">{t('bpmn.prop.servicetask.typeJava')}</option>
          <option value="script">{t('bpmn.prop.servicetask.typeScript')}</option>
          <option value="command">{t('bpmn.prop.servicetask.typeCommand')}</option>
        </select>
      </div>

      {c.serviceType === 'command' && (
        <div className="mb-4">
          <label className="mb-1 block text-sm font-medium text-gray-700">
            {t('bpmn.prop.servicetask.commandCode')}
          </label>
          <input
            type="text"
            value={c.commandCode ?? ''}
            onChange={(e) => onChange({ commandCode: e.target.value })}
            disabled={readOnly}
            className="w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm"
            placeholder="namespace:command_code"
            data-testid="bpm-sdk-svc-command-code"
          />
          <p className="mt-1 text-xs text-gray-500">
            {t('bpmn.prop.servicetask.commandCodeHint')}
          </p>
        </div>
      )}

      {c.serviceType === 'http' && (
        <div className="mb-4">
          <label className="mb-1 block text-sm font-medium text-gray-700">
            {t('bpmn.prop.servicetask.serviceUrl')}
          </label>
          <input
            type="text"
            value={c.serviceUrl ?? ''}
            onChange={(e) => onChange({ serviceUrl: e.target.value })}
            disabled={readOnly}
            className="w-full rounded-md border border-gray-300 px-3 py-2"
            placeholder="https://api.example.com/service"
            data-testid="bpm-sdk-svc-service-url"
          />
        </div>
      )}

      {c.serviceType === 'java' && (
        <div className="mb-4">
          <label className="mb-1 block text-sm font-medium text-gray-700">
            {t('bpmn.prop.servicetask.className')}
          </label>
          <input
            type="text"
            value={c.className ?? ''}
            onChange={(e) => onChange({ className: e.target.value })}
            disabled={readOnly}
            className="w-full rounded-md border border-gray-300 px-3 py-2"
            placeholder="com.example.MyService"
            data-testid="bpm-sdk-svc-class-name"
          />
        </div>
      )}

      {c.serviceType === 'script' && (
        <>
          <div className="mb-4">
            <label className="mb-1 block text-sm font-medium text-gray-700">
              {t('bpmn.prop.servicetask.scriptType')}
            </label>
            <select
              value={c.scriptType ?? 'groovy'}
              onChange={(e) =>
                onChange({ scriptType: e.target.value as ServiceTaskConfig['scriptType'] })
              }
              disabled={readOnly}
              className="w-full rounded-md border border-gray-300 px-3 py-2"
              data-testid="bpm-sdk-svc-script-type"
            >
              <option value="groovy">Groovy</option>
            </select>
          </div>
          <div className="mb-4">
            <label className="mb-1 block text-sm font-medium text-gray-700">
              {t('bpmn.prop.servicetask.scriptContent')}
            </label>
            <textarea
              value={c.scriptContent ?? ''}
              onChange={(e) => onChange({ scriptContent: e.target.value })}
              disabled={readOnly}
              className="w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm"
              rows={6}
              data-testid="bpm-sdk-svc-script-content"
            />
          </div>
        </>
      )}

      <div className="mb-4">
        <label className="flex items-center">
          <input
            type="checkbox"
            checked={c.async ?? false}
            onChange={(e) => onChange({ async: e.target.checked })}
            disabled={readOnly}
            className="mr-2"
            data-testid="bpm-sdk-svc-async"
          />
          <span className="text-sm font-medium text-gray-700">
            {t('bpmn.prop.servicetask.async')}
          </span>
        </label>
      </div>

      {/*
        NOTE: HookConfigSection (pre/post execution hooks) intentionally NOT
        ported in batch1 — it depends on shared.tsx (627 LOC) which is the
        single largest editor support file. Ported as part of a later batch.
      */}
    </>
  );
}
