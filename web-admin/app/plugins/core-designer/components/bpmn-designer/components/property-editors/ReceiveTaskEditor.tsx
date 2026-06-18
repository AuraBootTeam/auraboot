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
        GAP-252 (resolved): a receiveTask parks the process until a named message is
        delivered. messageRef is the message name; the backend correlates a delivered
        message (POST /api/bpm/process-instances/{id}/messages) to the parked receiveTask
        by this messageRef and resumes it via ExecutionCommandService.signal(). The
        message name is also emitted onto the <receiveTask messageRef="..."> in the BPMN.
      */}
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.receivetask.messageRef')}</label>
        <input
          type="text"
          value={config?.messageRef || ''}
          onChange={(e) => handleChange('messageRef', e.target.value)}
          placeholder="e.g. orderApproved"
          data-testid="receivetask-messageRef"
          className="w-full rounded-md border border-gray-300 px-3 py-2"
        />
        <p className="mt-1 text-xs text-gray-500">
          {t('bpmn.prop.receivetask.messageRefHint') ||
            'Message name this task waits for. Deliver it via POST /api/bpm/process-instances/{id}/messages to resume.'}
        </p>
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.receivetask.messageType')}</label>
        <input
          type="text"
          value={config?.messageType || ''}
          onChange={(e) => handleChange('messageType', e.target.value)}
          placeholder="optional"
          data-testid="receivetask-messageType"
          className="w-full rounded-md border border-gray-300 px-3 py-2"
        />
      </div>
    </>
  );
}
