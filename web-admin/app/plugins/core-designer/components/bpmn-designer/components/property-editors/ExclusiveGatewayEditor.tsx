/**
 * Property editor for Exclusive Gateway nodes.
 * Replaces the shared GatewayEditor with exclusive-specific features:
 * - Description
 * - Default flow dropdown (populated from outgoing edges)
 * - Condition summary (read-only list of outgoing edge conditions)
 */

import type { ExclusiveGatewayConfig } from '~/plugins/core-designer/components/bpmn-designer/types';
import { useI18n } from '~/contexts/I18nContext';

interface ExclusiveGatewayEditorProps {
  config?: ExclusiveGatewayConfig;
  onChange: (config: ExclusiveGatewayConfig) => void;
  outgoingEdges: Array<{ id: string; label?: string; condition?: string }>;
}

export function ExclusiveGatewayEditor({
  config,
  onChange,
  outgoingEdges,
}: ExclusiveGatewayEditorProps) {
  const { t } = useI18n();

  const handleChange = (field: keyof ExclusiveGatewayConfig, value: string) => {
    onChange({ ...config, name: config?.name || '', [field]: value });
  };

  return (
    <>
      {/* Description */}
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.common.description')}</label>
        <textarea
          value={config?.description || ''}
          onChange={(e) => handleChange('description', e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
          rows={2}
          placeholder={t('bpmn.gateway.exclusiveDescPlaceholder')}
        />
      </div>

      {/* Default flow dropdown */}
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.gateway.defaultFlow')}</label>
        <select
          value={config?.defaultFlow || ''}
          onChange={(e) => handleChange('defaultFlow', e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
          data-testid="gateway-default-flow"
        >
          <option value="">{t('bpmn.gateway.noDefaultFlow')}</option>
          {outgoingEdges.map((edge) => (
            <option key={edge.id} value={edge.id}>
              {edge.label || edge.id}
            </option>
          ))}
        </select>
        <p className="mt-1 text-xs text-gray-500">
          {t('bpmn.gateway.defaultFlowHint')}
        </p>
      </div>

      {/* Condition summary (read-only) */}
      {outgoingEdges.length > 0 && (
        <div className="mb-4">
          <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.gateway.outgoingConditions')}</label>
          <div className="rounded-md border border-gray-200 bg-gray-50 p-3">
            {outgoingEdges.map((edge) => {
              const isDefault = config?.defaultFlow === edge.id;
              return (
                <div
                  key={edge.id}
                  className="flex items-start gap-2 py-1 text-sm text-gray-700"
                >
                  <span className="shrink-0 text-gray-400">&rarr;</span>
                  <div className="min-w-0 flex-1">
                    {isDefault ? (
                      <span className="italic text-gray-500">{t('bpmn.gateway.defaultFlowTag')}</span>
                    ) : (
                      <>
                        <span className="font-medium">{edge.label || edge.id}</span>
                        {edge.condition && (
                          <span className="ml-1 text-gray-500">: {edge.condition}</span>
                        )}
                        {!edge.condition && !isDefault && (
                          <span className="ml-1 text-xs text-amber-600">{t('bpmn.gateway.noConditionSet')}</span>
                        )}
                      </>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </>
  );
}
