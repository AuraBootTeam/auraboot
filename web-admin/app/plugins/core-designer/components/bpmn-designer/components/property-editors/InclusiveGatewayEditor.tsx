/**
 * Property editor for Inclusive Gateway nodes.
 * Features:
 * - Description
 * - Default flow dropdown (populated from outgoing edges)
 * - Completion condition (for join behavior)
 */

import type { InclusiveGatewayConfig } from '~/plugins/core-designer/components/bpmn-designer/types';
import { useI18n } from '~/contexts/I18nContext';

// Extended config with completionCondition for join behavior
interface ExtendedInclusiveGatewayConfig extends InclusiveGatewayConfig {
  completionCondition?: string;
}

interface InclusiveGatewayEditorProps {
  config?: ExtendedInclusiveGatewayConfig;
  onChange: (config: ExtendedInclusiveGatewayConfig) => void;
  outgoingEdges: Array<{ id: string; label?: string; condition?: string }>;
}

export function InclusiveGatewayEditor({
  config,
  onChange,
  outgoingEdges,
}: InclusiveGatewayEditorProps) {
  const { t } = useI18n();

  const handleChange = (field: keyof ExtendedInclusiveGatewayConfig, value: string) => {
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
          placeholder={t('bpmn.gateway.inclusiveDescPlaceholder')}
        />
      </div>

      {/* Default flow dropdown */}
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.gateway.defaultFlow')}</label>
        <select
          value={config?.defaultFlow || ''}
          onChange={(e) => handleChange('defaultFlow', e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
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

      {/*
        Completion condition is UI-only: JsonToBpmnConverter does not emit a
        completionCondition element on inclusiveGateway, so SmartEngine falls
        back to its default join semantics. Disabled until runtime support lands.
      */}
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.gateway.completionCondition')}</label>
        <textarea
          value={config?.completionCondition || ''}
          disabled
          readOnly
          data-testid="inclusivegateway-completionCondition"
          className="w-full rounded-md border border-gray-300 bg-gray-100 px-3 py-2 text-sm text-gray-500"
          rows={2}
          placeholder="${nrOfCompletedInstances >= 1}"
        />
        <p className="mt-1 text-xs text-amber-600">{t('bpmn.prop.common.unsupportedHint')}</p>
      </div>
    </>
  );
}
