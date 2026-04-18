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
        messageRef / messageType are UI-only placeholders: JsonToBpmnConverter does
        not emit a <bpmn:message> element, so SmartEngine has no trigger to advance
        the ReceiveTask. Fields are disabled until runtime support lands.
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
        <p className="mt-1 text-xs text-amber-600">{t('bpmn.prop.common.unsupportedHint')}</p>
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
        <p className="mt-1 text-xs text-amber-600">{t('bpmn.prop.common.unsupportedHint')}</p>
      </div>
    </>
  );
}
