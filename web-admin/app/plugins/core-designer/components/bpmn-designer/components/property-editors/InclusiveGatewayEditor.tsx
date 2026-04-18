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
        GAP-252: Completion condition on inclusiveGateway is unsupported end-to-end:
        - Runtime: SmartEngine InclusiveGatewayParser only reads id/name/properties
          (see core/.../bpmn/assembly/gateway/parser/InclusiveGatewayParser.java)
          and InclusiveGatewayBehavior applies default BPMN 2.0 N-of-N join semantics.
          No `CompletionCondition` model exists outside MultiInstanceLoopCharacteristics.
        - Converter: JsonToBpmnConverter.writeInclusiveGateway does not emit
          <completionCondition>; silently dropped even if UI were enabled.
        - GAP-253 fixed the ClassCast in the join path but did NOT add threshold logic.
        Disabled with concrete reason; re-enable requires SmartEngine parser + behavior
        changes, not just a UI flip.
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
        <p className="mt-1 text-xs text-amber-600">
          {t('bpmn.prop.inclusivegateway.completionConditionUnsupported') ||
            'Unsupported: SmartEngine InclusiveGatewayParser does not read <completionCondition>; default BPMN join semantics apply. Needs runtime support (parser + behavior), not just UI enable.'}
        </p>
      </div>
    </>
  );
}
