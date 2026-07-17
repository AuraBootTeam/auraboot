/**
 * Property editor for ServiceTask nodes.
 */

import { useEffect, useMemo, useState } from 'react';
import type { ServiceTaskConfig } from '~/plugins/core-designer/components/bpmn-designer/types';
import {
  createDecisionApi,
  type DecisionAction,
  type DecisionActionCatalog,
  type HttpClient,
} from '~/shared/decision/api/decisionApi';
import { resolveDecisionActionAvailability } from '~/shared/decision/ui/actionAvailability';
import { getApiService } from '~/shared/services/ApiService';
import { HookConfigSection } from './shared';
import { useI18n } from '~/contexts/I18nContext';

const BUILTIN_BPM_ACTIONS: DecisionAction[] = [
  { actionType: 'NOTIFY', label: '发送通知', category: 'messaging', consumerTypes: ['BPM'] },
  { actionType: 'SEND_SMS', label: '发送短信', category: 'messaging', consumerTypes: ['BPM'] },
  { actionType: 'SEND_IM', label: '发送 IM 消息', category: 'messaging', consumerTypes: ['BPM'] },
  { actionType: 'WEBHOOK', label: '发送 Webhook', category: 'integration', consumerTypes: ['BPM'] },
  { actionType: 'CREATE_TASK', label: '创建任务', category: 'workflow', consumerTypes: ['BPM'] },
  { actionType: 'CC_TASK', label: '抄送任务', category: 'collaboration', consumerTypes: ['BPM'] },
  { actionType: 'WRITE_AUDIT', label: '写入审计', category: 'governance', consumerTypes: ['BPM'] },
];
const BPM_CONSUMER_TYPE = 'BPM';

function defaultApi() {
  const service = getApiService();
  const http: HttpClient = {
    get: <T,>(endpoint: string, params?: Record<string, unknown>) =>
      service.get<T>(endpoint, params),
    post: <T,>(endpoint: string, body?: unknown) => service.post<T>(endpoint, body),
    delete: <T,>(endpoint: string) => service.delete<T>(endpoint),
  };
  return createDecisionApi(http);
}

function catalogWithFallback(catalog: DecisionAction[]) {
  const seen = new Set<string>();
  return [...catalog, ...BUILTIN_BPM_ACTIONS]
    .filter((action) => {
      if (!action.actionType || seen.has(action.actionType)) return false;
      seen.add(action.actionType);
      return !action.consumerTypes?.length || action.consumerTypes.includes('BPM');
    });
}

function actionAvailability(action?: DecisionAction) {
  return resolveDecisionActionAvailability(action, BPM_CONSUMER_TYPE);
}

function actionOptionLabel(action: DecisionAction) {
  const unavailable = actionAvailability(action).unavailable;
  return `${action.label || action.actionType}${unavailable ? '（不可用）' : ''}`;
}

export function ServiceTaskEditor({
  config,
  onChange,
  api,
}: {
  config?: ServiceTaskConfig;
  onChange: (config: ServiceTaskConfig) => void;
  api?: {
    getActionCatalog: () => Promise<DecisionActionCatalog>;
  };
}) {
  const { t } = useI18n();
  const actionApi = useMemo(() => api ?? defaultApi(), [api]);
  const [actionCatalog, setActionCatalog] = useState<DecisionAction[]>([]);

  useEffect(() => {
    let cancelled = false;
    actionApi
      .getActionCatalog()
      .then((catalog) => {
        if (!cancelled) setActionCatalog(Array.isArray(catalog.actions) ? catalog.actions : []);
      })
      .catch(() => {
        if (!cancelled) setActionCatalog([]);
      });
    return () => {
      cancelled = true;
    };
  }, [actionApi]);

  const handleChange = (field: keyof ServiceTaskConfig, value: any) => {
    onChange({ ...config, [field]: value } as ServiceTaskConfig);
  };

  const actionOptions = useMemo(() => catalogWithFallback(actionCatalog), [actionCatalog]);
  const selectedAction = actionOptions.find((action) => action.actionType === config?.actionType);
  const selectedAvailability = actionAvailability(selectedAction);

  return (
    <>
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.common.description')}</label>
        <textarea
          value={config?.description || ''}
          onChange={(e) => handleChange('description', e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          rows={2}
        />
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.servicetask.serviceType')}</label>
        <select
          value={config?.serviceType || 'http'}
          onChange={(e) => handleChange('serviceType', e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          data-testid="servicetask-service-type"
        >
          <option value="http">{t('bpmn.prop.servicetask.typeHttp')}</option>
          <option value="java">{t('bpmn.prop.servicetask.typeJava')}</option>
          <option value="script">{t('bpmn.prop.servicetask.typeScript')}</option>
          <option value="command">{t('bpmn.prop.servicetask.typeCommand')}</option>
          <option value="action">平台动作</option>
        </select>
      </div>

      {config?.serviceType === 'command' && (
        <div className="mb-4">
          <label className="mb-1 block text-sm font-medium text-gray-700">
            {t('bpmn.prop.servicetask.commandCode')}
          </label>
          <input
            type="text"
            value={config?.commandCode || ''}
            onChange={(e) => handleChange('commandCode', e.target.value)}
            className="w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm"
            placeholder="namespace:command_code"
            data-testid="servicetask-command-code"
          />
          <p className="mt-1 text-xs text-gray-500">
            {t('bpmn.prop.servicetask.commandCodeHint')}
          </p>
        </div>
      )}

      {config?.serviceType === 'action' && (
        <div className="mb-4 rounded-md border border-gray-200 bg-gray-50 p-3" data-testid="servicetask-action-panel">
          <div className="mb-3">
            <label className="mb-1 block text-sm font-medium text-gray-700" htmlFor="servicetask-action-type">
              平台动作
            </label>
            <select
              id="servicetask-action-type"
              value={config?.actionType || ''}
              onChange={(e) => handleChange('actionType', e.target.value)}
              className="w-full rounded-md border border-gray-300 bg-white px-3 py-2"
              data-testid="servicetask-action-type"
            >
              <option value="">选择动作</option>
              {actionOptions.map((action) => (
                <option key={action.actionType} value={action.actionType}>
                  {actionOptionLabel(action)}
                </option>
              ))}
            </select>
          </div>

          {config?.actionType && (
            <div className="mb-3 rounded border border-gray-200 bg-white p-2" data-testid="servicetask-action-summary">
              <div className="flex items-center justify-between gap-2">
                <span className="text-sm font-medium text-gray-700">
                  {selectedAction?.label || config.actionType}
                </span>
                {selectedAvailability.unavailable && (
                  <span className="rounded bg-amber-100 px-2 py-0.5 text-xs font-semibold text-amber-700">
                    不可用
                  </span>
                )}
              </div>
              {selectedAction?.description && (
                <p className="mt-1 text-xs text-gray-500">{selectedAction.description}</p>
              )}
              {selectedAvailability.unavailable && (
                <p className="mt-1 text-xs text-amber-700" data-testid="servicetask-action-availability">
                  <span>{selectedAvailability.reason}</span>
                  {selectedAvailability.providerSummary && (
                    <span className="mt-1 block" data-testid="servicetask-action-provider">
                      {selectedAvailability.providerSummary}
                    </span>
                  )}
                </p>
              )}
            </div>
          )}

          <div className="mb-3">
            <label className="mb-1 block text-xs font-medium text-gray-600" htmlFor="servicetask-action-target">
              接收对象 / 目标表达式
            </label>
            <input
              id="servicetask-action-target"
              type="text"
              value={config?.actionTarget || ''}
              onChange={(e) => handleChange('actionTarget', e.target.value)}
              className="w-full rounded border border-gray-300 bg-white px-2 py-1.5 text-sm"
              placeholder="USER:${process.requesterId} / PHONE:${record.phone}"
              data-testid="servicetask-action-target"
            />
          </div>

          <div className="mb-3">
            <label className="mb-1 block text-xs font-medium text-gray-600" htmlFor="servicetask-action-payload">
              动作负载 JSON
            </label>
            <textarea
              id="servicetask-action-payload"
              value={config?.actionPayloadJson || '{}'}
              onChange={(e) => handleChange('actionPayloadJson', e.target.value)}
              className="w-full rounded border border-gray-300 bg-white px-2 py-1.5 font-mono text-xs"
              rows={4}
              placeholder='{"content":"审批已超时: ${process.businessKey}"}'
              data-testid="servicetask-action-payload"
            />
          </div>

          <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
            <div>
              <label className="mb-1 block text-xs font-medium text-gray-600" htmlFor="servicetask-action-result-var">
                结果变量
              </label>
              <input
                id="servicetask-action-result-var"
                type="text"
                value={config?.actionResultVar || ''}
                onChange={(e) => handleChange('actionResultVar', e.target.value)}
                className="w-full rounded border border-gray-300 bg-white px-2 py-1.5 text-sm"
                placeholder="actionResult"
                data-testid="servicetask-action-result-var"
              />
            </div>
            <div>
              <label className="mb-1 block text-xs font-medium text-gray-600" htmlFor="servicetask-action-idempotency">
                幂等键
              </label>
              <input
                id="servicetask-action-idempotency"
                type="text"
                value={config?.actionIdempotencyKey || ''}
                onChange={(e) => handleChange('actionIdempotencyKey', e.target.value)}
                className="w-full rounded border border-gray-300 bg-white px-2 py-1.5 text-sm"
                placeholder="${process.instanceId}:${nodeId}:${actionType}"
                data-testid="servicetask-action-idempotency"
              />
            </div>
          </div>
        </div>
      )}

      {config?.serviceType === 'http' && (
        <div className="mb-4">
          <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.servicetask.serviceUrl')}</label>
          <input
            type="text"
            value={config?.serviceUrl || ''}
            onChange={(e) => handleChange('serviceUrl', e.target.value)}
            className="w-full rounded-md border border-gray-300 px-3 py-2"
            placeholder="https://api.example.com/service"
            data-testid="servicetask-service-url"
          />
        </div>
      )}

      {config?.serviceType === 'java' && (
        <div className="mb-4">
          <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.servicetask.className')}</label>
          <input
            type="text"
            value={config?.className || ''}
            onChange={(e) => handleChange('className', e.target.value)}
            className="w-full rounded-md border border-gray-300 px-3 py-2"
            placeholder="com.example.MyService"
            data-testid="servicetask-class-name"
          />
        </div>
      )}

      {config?.serviceType === 'script' && (
        <>
          <div className="mb-4">
            <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.servicetask.scriptType')}</label>
            {/*
              Only Groovy is supported by the SmartEngine script runtime.
              Selecting JavaScript would deploy a process that fails at runtime,
              so the option is removed from the dropdown.
            */}
            <select
              value={config?.scriptType || 'groovy'}
              onChange={(e) => handleChange('scriptType', e.target.value)}
              className="w-full rounded-md border border-gray-300 px-3 py-2"
              data-testid="servicetask-script-type"
            >
              <option value="groovy">Groovy</option>
            </select>
          </div>
          <div className="mb-4">
            <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.servicetask.scriptContent')}</label>
            <textarea
              value={config?.scriptContent || ''}
              onChange={(e) => handleChange('scriptContent', e.target.value)}
              className="w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm"
              rows={6}
              data-testid="servicetask-script-content"
            />
          </div>
        </>
      )}

      <div className="mb-4">
        <label className="flex items-center">
          <input
            type="checkbox"
            checked={config?.async || false}
            onChange={(e) => handleChange('async', e.target.checked)}
            className="mr-2"
            data-testid="servicetask-async"
          />
          <span className="text-sm font-medium text-gray-700">{t('bpmn.prop.servicetask.async')}</span>
        </label>
      </div>

      {/* Hook configuration */}
      <HookConfigSection
        hooks={config?.hooks || []}
        onChange={(hooks) => handleChange('hooks', hooks)}
      />
    </>
  );
}
