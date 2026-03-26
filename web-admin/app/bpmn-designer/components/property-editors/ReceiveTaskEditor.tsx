/**
 * Property editor for ReceiveTask nodes.
 */

import type { ReceiveTaskConfig } from '~/bpmn-designer/types';

export function ReceiveTaskEditor({
  config,
  onChange,
}: {
  config?: ReceiveTaskConfig;
  onChange: (config: ReceiveTaskConfig) => void;
}) {
  const handleChange = (field: keyof ReceiveTaskConfig, value: any) => {
    onChange({ ...config, [field]: value } as ReceiveTaskConfig);
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
        <label className="mb-1 block text-sm font-medium text-gray-700">消息引用</label>
        <input
          type="text"
          value={config?.messageRef || ''}
          onChange={(e) => handleChange('messageRef', e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
        />
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">消息类型</label>
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
