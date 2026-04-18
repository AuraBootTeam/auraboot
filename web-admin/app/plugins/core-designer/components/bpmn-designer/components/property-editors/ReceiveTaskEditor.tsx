/**
 * Property editor for ReceiveTask nodes.
 */

import { useI18n } from '~/contexts/I18nContext';
import type { ReceiveTaskConfig } from '~/plugins/core-designer/components/bpmn-designer/types';

export function ReceiveTaskEditor({
  config,
  onChange,
}: {
  config?: ReceiveTaskConfig;
  onChange: (config: ReceiveTaskConfig) => void;
}) {
  const { t } = useI18n();
  const handleChange = (field: keyof ReceiveTaskConfig, value: any) => {
    onChange({ ...config, [field]: value } as ReceiveTaskConfig);
  };

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

      {/*
        GAP-252: messageRef / messageType on receiveTask are unsupported end-to-end:
        - Runtime: SmartEngine ReceiveTaskParser only reads id/name/properties
          (core/.../bpmn/assembly/task/parser/ReceiveTaskParser.java) and
          ReceiveTaskBehavior is a pure wait-for-signal() activity. There is no
          <bpmn:message>/<messageEventDefinition> parser and no message correlation.
        - Converter: JsonToBpmnConverter.writeReceiveTask emits only id+name.
        Fields disabled with concrete reason; re-enable requires adding a Message
        model + parser + correlation layer in SmartEngine, not just a UI flip.
      */}
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.receivetask.messageRef')}</label>
        <input
          type="text"
          value={config?.messageRef || ''}
          disabled
          readOnly
          data-testid="receivetask-messageRef"
          className="w-full rounded-md border border-gray-300 bg-gray-100 px-3 py-2 text-gray-500"
        />
        <p className="mt-1 text-xs text-amber-600">
          {t('bpmn.prop.receivetask.messageUnsupported') ||
            'Unsupported: SmartEngine has no <bpmn:message> parser/correlation. ReceiveTask only advances via signal() API. Needs runtime support, not just UI enable.'}
        </p>
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.receivetask.messageType')}</label>
        <input
          type="text"
          value={config?.messageType || ''}
          disabled
          readOnly
          data-testid="receivetask-messageType"
          className="w-full rounded-md border border-gray-300 bg-gray-100 px-3 py-2 text-gray-500"
        />
        <p className="mt-1 text-xs text-amber-600">
          {t('bpmn.prop.receivetask.messageUnsupported') ||
            'Unsupported: SmartEngine has no <bpmn:message> parser/correlation. ReceiveTask only advances via signal() API. Needs runtime support, not just UI enable.'}
        </p>
      </div>
    </>
  );
}
