/**
 * Property editor for CallActivity nodes.
 */

import type { CallActivityConfig } from '~/bpmn-designer/types';

export function CallActivityEditor({
  config,
  onChange,
}: {
  config?: CallActivityConfig;
  onChange: (config: CallActivityConfig) => void;
}) {
  const handleChange = (field: keyof CallActivityConfig, value: any) => {
    onChange({
      ...config,
      calledProcessKey: config?.calledProcessKey || '',
      [field]: value,
    } as CallActivityConfig);
  };

  return (
    <>
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">描述</label>
        <textarea
          value={config?.description || ''}
          onChange={(e) => handleChange('description', e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          rows={2}
        />
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">调用流程标识</label>
        <input
          type="text"
          value={config?.calledProcessKey || ''}
          onChange={(e) => handleChange('calledProcessKey', e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          placeholder="process-key"
        />
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">调用流程版本</label>
        <input
          type="text"
          value={config?.calledProcessVersion || ''}
          onChange={(e) => handleChange('calledProcessVersion', e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          placeholder="latest"
        />
      </div>
    </>
  );
}
