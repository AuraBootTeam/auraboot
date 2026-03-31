/**
 * Property editor for ServiceTask nodes.
 */

import type { ServiceTaskConfig } from '~/bpmn-designer/types';
import { HookConfigSection } from './shared';
import { useI18n } from '~/contexts/I18nContext';

export function ServiceTaskEditor({
  config,
  onChange,
}: {
  config?: ServiceTaskConfig;
  onChange: (config: ServiceTaskConfig) => void;
}) {
  const { t } = useI18n();

  const handleChange = (field: keyof ServiceTaskConfig, value: any) => {
    onChange({ ...config, [field]: value } as ServiceTaskConfig);
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
        <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.servicetask.serviceType')}</label>
        <select
          value={config?.serviceType || 'http'}
          onChange={(e) => handleChange('serviceType', e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
        >
          <option value="http">{t('bpmn.prop.servicetask.typeHttp')}</option>
          <option value="java">{t('bpmn.prop.servicetask.typeJava')}</option>
          <option value="script">{t('bpmn.prop.servicetask.typeScript')}</option>
        </select>
      </div>

      {config?.serviceType === 'http' && (
        <div className="mb-4">
          <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.servicetask.serviceUrl')}</label>
          <input
            type="text"
            value={config?.serviceUrl || ''}
            onChange={(e) => handleChange('serviceUrl', e.target.value)}
            className="w-full rounded-md border border-gray-300 px-3 py-2"
            placeholder="https://api.example.com/service"
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
          />
        </div>
      )}

      {config?.serviceType === 'script' && (
        <>
          <div className="mb-4">
            <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.servicetask.scriptType')}</label>
            <select
              value={config?.scriptType || 'javascript'}
              onChange={(e) => handleChange('scriptType', e.target.value)}
              className="w-full rounded-md border border-gray-300 px-3 py-2"
            >
              <option value="javascript">JavaScript</option>
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
