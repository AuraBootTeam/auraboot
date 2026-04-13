/**
 * Property editors for Start and End event nodes.
 */

import { useI18n } from '~/contexts/I18nContext';
import type { StartEventConfig, EndEventConfig } from '~/plugins/core-designer/components/bpmn-designer/types';

export function StartEventEditor({
  config,
  onChange,
}: {
  config?: StartEventConfig;
  onChange: (config: StartEventConfig) => void;
}) {
  const { t } = useI18n();
  const handleChange = (field: keyof StartEventConfig, value: any) => {
    onChange({ ...config, [field]: value } as StartEventConfig);
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
        <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.startevent.initiator')}</label>
        <input
          type="text"
          value={config?.initiator || 'initiator'}
          onChange={(e) => handleChange('initiator', e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
        />
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.startevent.formKey')}</label>
        <input
          type="text"
          value={config?.formKey || ''}
          onChange={(e) => handleChange('formKey', e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          placeholder={t('bpmn.prop.startevent.formKeyPlaceholder')}
        />
      </div>
    </>
  );
}

export function EndEventEditor({
  config,
  onChange,
}: {
  config?: EndEventConfig;
  onChange: (config: EndEventConfig) => void;
}) {
  const { t } = useI18n();
  const handleChange = (field: keyof EndEventConfig, value: any) => {
    onChange({ ...config, [field]: value } as EndEventConfig);
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
        <label className="flex items-center">
          <input
            type="checkbox"
            checked={config?.terminateAll || false}
            onChange={(e) => handleChange('terminateAll', e.target.checked)}
            className="mr-2"
          />
          <span className="text-sm font-medium text-gray-700">{t('bpmn.prop.endevent.terminateAll')}</span>
        </label>
      </div>
    </>
  );
}
