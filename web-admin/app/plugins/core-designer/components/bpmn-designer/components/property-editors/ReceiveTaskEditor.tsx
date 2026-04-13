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

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.receivetask.messageRef')}</label>
        <input
          type="text"
          value={config?.messageRef || ''}
          onChange={(e) => handleChange('messageRef', e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
        />
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.receivetask.messageType')}</label>
        <input
          type="text"
          value={config?.messageType || ''}
          onChange={(e) => handleChange('messageType', e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
        />
      </div>
    </>
  );
}
