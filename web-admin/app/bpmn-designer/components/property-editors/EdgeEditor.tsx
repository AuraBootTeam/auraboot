/**
 * Property editor for sequence flow edges.
 * Uses ConditionExpressionEditor for condition editing (simple + advanced modes).
 */

import { useI18n } from '~/contexts/I18nContext';
import type { BPMNEdgeData, ConditionExpression } from '~/bpmn-designer/types';
import { ConditionExpressionEditor } from './ConditionExpressionEditor';

export function EdgeEditor({
  edgeId,
  data,
  onUpdate,
}: {
  edgeId: string;
  data?: BPMNEdgeData;
  onUpdate: (edgeId: string, data: Partial<BPMNEdgeData>) => void;
}) {
  const { t } = useI18n();
  return (
    <>
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.edge.label')}</label>
        <input
          type="text"
          value={data?.label || ''}
          onChange={(e) => onUpdate(edgeId, { label: e.target.value })}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          data-testid="edge-label-input"
        />
      </div>

      <ConditionExpressionEditor
        condition={data?.condition}
        onChange={(condition: ConditionExpression) => onUpdate(edgeId, { condition })}
      />

      <div className="mb-4">
        <label className="flex items-center">
          <input
            type="checkbox"
            checked={data?.isDefault || false}
            onChange={(e) => onUpdate(edgeId, { isDefault: e.target.checked })}
            className="mr-2"
            data-testid="edge-default-checkbox"
          />
          <span className="text-sm font-medium text-gray-700">{t('bpmn.prop.edge.defaultFlow')}</span>
        </label>
        <p className="mt-1 text-xs text-gray-400">
          {t('bpmn.prop.edge.defaultFlowHint')}
        </p>
      </div>
    </>
  );
}
